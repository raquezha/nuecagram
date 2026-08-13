#!/usr/bin/env bash
#
# Production deployment entrypoint for Nuecagram.
# Manages zero-downtime deployment, image state persistence, and automatic health rollback.
#

set -euo pipefail
umask 077

readonly PROJECT_ROOT="${PROJECT_ROOT:-/opt/nuecagram}"
readonly ENV_FILE="${ENV_FILE:-/opt/nuecagram/.env}"
readonly COMPOSE_FILE="${COMPOSE_FILE:-/opt/nuecagram/compose.yaml}"
readonly STATE_FILE="${STATE_FILE:-/var/lib/nuecagram/previous-image}"
readonly HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-300}"

readonly IMAGE_PATTERN='^(raquezha/nuecagram:v[0-9]+\.[0-9]+\.[0-9]+|raquezha/nuecagram:sha-[0-9a-fA-F]{12}|raquezha/nuecagram@sha256:[0-9a-fA-F]{64})$'

die() {
  printf 'Error: %s\n' "$1" >&2
  exit 1
}

usage() {
  cat <<EOF >&2
Usage: $(basename "$0") --mode <deploy|rollback> --image <image-ref|previous>

Options:
  --mode    Operation mode: 'deploy' or 'rollback'
  --image   Target image reference (tag or digest) or 'previous' for rollback
EOF
  exit 1
}

is_valid_image_ref() {
  [[ "$1" =~ $IMAGE_PATTERN ]]
}

validate_environment() {
  local skip_root="${TEST_SKIP_ROOT_CHECK:-0}"
  local skip_docker="${TEST_SKIP_DOCKER_CHECK:-0}"

  if [[ "$skip_root" -ne 1 && "$(id -u)" -ne 0 ]]; then
    die "must run as root"
  fi

  if [[ "$skip_docker" -ne 1 ]] && ! docker compose version >/dev/null 2>&1; then
    die "docker compose plugin missing"
  fi

  for file in "$ENV_FILE" "$COMPOSE_FILE"; do
    [[ -f "$file" ]] || die "$file does not exist"
    if [[ "$skip_root" -ne 1 ]]; then
      [[ "$(stat -c %u "$file")" -eq 0 ]] || die "$file must be owned by root"
    fi
  done

  if [[ "$skip_root" -ne 1 && "$(stat -c %a "$ENV_FILE")" != "600" ]]; then
    die "$ENV_FILE must have mode 600"
  fi
}

compose() {
  docker compose \
    --project-directory "$PROJECT_ROOT" \
    --env-file "$ENV_FILE" \
    -f "$COMPOSE_FILE" \
    "$@"
}

persist_env_image() {
  local new_image="$1"
  local tmp_env

  tmp_env="$(mktemp "${ENV_FILE}.tmp.XXXXXX")"
  chmod 600 "$tmp_env"

  if grep -q '^NUECAGRAM_IMAGE=' "$ENV_FILE"; then
    sed "s|^NUECAGRAM_IMAGE=.*|NUECAGRAM_IMAGE=$new_image|" "$ENV_FILE" > "$tmp_env"
  else
    cp "$ENV_FILE" "$tmp_env"
    printf '\nNUECAGRAM_IMAGE=%s\n' "$new_image" >> "$tmp_env"
  fi

  chmod 600 "$tmp_env"
  mv "$tmp_env" "$ENV_FILE"
}

get_current_running_image() {
  if [[ "${TEST_SKIP_DOCKER_CHECK:-0}" -eq 1 ]]; then
    return 0
  fi

  local container image
  container="$(compose ps -q app 2>/dev/null || true)"
  if [[ -n "$container" ]]; then
    image="$(docker inspect --format '{{.Config.Image}}' "$container" 2>/dev/null || true)"
    if is_valid_image_ref "$image"; then
      printf '%s' "$image"
    else
      printf 'Warning: current running image (%s) is untracked; ignoring for rollback\n' "$image" >&2
    fi
  fi
}

wait_for_health() {
  if [[ "${TEST_MOCK_READY:-0}" -eq 1 ]]; then
    return 0
  fi

  local start_time container health_status
  start_time="$(date +%s)"

  while true; do
    container="$(compose ps -q app 2>/dev/null || true)"
    health_status=""
    if [[ -n "$container" ]]; then
      health_status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$container" 2>/dev/null || true)"
    fi

    if [[ "$health_status" == "healthy" ]]; then
      return 0
    fi

    if (( $(date +%s) - start_time >= HEALTH_TIMEOUT_SECONDS )); then
      return 1
    fi
    sleep 5
  done
}

deploy_image() {
  local target_image="$1"

  export NUECAGRAM_ENV_FILE="$ENV_FILE"
  export NUECAGRAM_IMAGE="$target_image"

  if [[ "${TEST_DRY_RUN:-0}" -eq 1 ]]; then
    persist_env_image "$target_image"
    printf 'Successfully deployed %s\n' "$target_image"
    return 0
  fi

  if compose pull app && compose up -d app && wait_for_health; then
    persist_env_image "$target_image"
    printf 'Successfully deployed %s\n' "$target_image"
    return 0
  fi

  return 1
}

record_previous_image() {
  local current_image="$1"
  local target_image="$2"

  if [[ -n "$current_image" && "$current_image" != "$target_image" ]]; then
    install -d -m 700 "$(dirname "$STATE_FILE")"
    printf '%s\n' "$current_image" > "${STATE_FILE}.tmp"
    mv "${STATE_FILE}.tmp" "$STATE_FILE"
  fi
}

resolve_rollback_image() {
  local requested_image="$1"

  if [[ "$requested_image" == "previous" ]]; then
    if [[ ! -s "$STATE_FILE" ]]; then
      die "No previous image recorded for rollback"
    fi
    requested_image="$(tr -d '[:space:]' < "$STATE_FILE")"
  fi

  if ! is_valid_image_ref "$requested_image"; then
    die "Invalid rollback image reference: $requested_image"
  fi

  printf '%s' "$requested_image"
}

main() {
  local mode="" image=""

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --mode) mode="${2:-}"; shift 2 ;;
      --image) image="${2:-}"; shift 2 ;;
      *) usage ;;
    esac
  done

  [[ "$mode" == "deploy" || "$mode" == "rollback" ]] || usage
  [[ -n "$image" ]] || usage

  validate_environment

  if [[ "$mode" == "deploy" ]]; then
    if ! is_valid_image_ref "$image"; then
      die "Deploy image reference is invalid: $image"
    fi
  else
    image="$(resolve_rollback_image "$image")"
  fi

  local current_image
  current_image="$(get_current_running_image)"

  record_previous_image "$current_image" "$image"

  if deploy_image "$image"; then
    exit 0
  fi

  printf 'Deployment failed health check; attempting rollback...\n' >&2
  if [[ -n "$current_image" ]]; then
    if deploy_image "$current_image"; then
      printf 'Rollback successful: restored %s\n' "$current_image" >&2
      exit 1
    fi
  fi

  die "Automatic rollback failed; manual intervention required."
}

main "$@"
