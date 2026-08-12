#!/usr/bin/env bash
set -euo pipefail
umask 077

readonly PROJECT_ROOT=/opt/nuecagram
readonly ENV_FILE=/opt/nuecagram/.env
readonly COMPOSE_FILE=/opt/nuecagram/compose.yaml
readonly STATE_FILE=/var/lib/nuecagram/previous-image
readonly HEALTH_TIMEOUT_SECONDS=300
readonly IMAGE_PATTERN='^(raquezha/nuecagram:v[0-9]+\.[0-9]+\.[0-9]+|raquezha/nuecagram@sha256:[0-9a-f]{64})$'

usage() {
  echo "Usage: $0 --mode <deploy|rollback> --image <digest|previous>" >&2
}

mode=""
image=""
while [ $# -gt 0 ]; do
  case "$1" in
    --mode) mode="${2:-}"; shift 2 ;;
    --image) image="${2:-}"; shift 2 ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [ "$mode" != deploy ] && [ "$mode" != rollback ]; then
  echo "mode must be deploy or rollback" >&2
  exit 1
fi
if [ "$mode" = deploy ] && [[ ! "$image" =~ $IMAGE_PATTERN ]]; then
  echo "deploy image must be a raquezha/nuecagram version or digest" >&2
  exit 1
fi
if [ "$mode" = rollback ] && [ "$image" != previous ] && [[ ! "$image" =~ $IMAGE_PATTERN ]]; then
  echo "rollback image must be previous or an immutable raquezha/nuecagram digest" >&2
  exit 1
fi

if [ "$(id -u)" -ne 0 ]; then
  echo "must run as root" >&2
  exit 1
fi
if ! docker compose version >/dev/null 2>&1; then
  echo "docker compose plugin missing" >&2
  exit 1
fi
for file in "$ENV_FILE" "$COMPOSE_FILE"; do
  if [ ! -f "$file" ] || [ "$(stat -c %u "$file")" -ne 0 ]; then
    echo "$file must exist and be owned by root" >&2
    exit 1
  fi
done
if [ "$(stat -c %a "$ENV_FILE")" != 600 ]; then
  echo "$ENV_FILE must have mode 600" >&2
  exit 1
fi

install -d -o root -g root -m 700 "$(dirname "$STATE_FILE")"
export NUECAGRAM_ENV_FILE="$ENV_FILE"
export NUECAGRAM_IMAGE="$image"

compose() {
  docker compose \
    --project-directory "$PROJECT_ROOT" \
    --env-file "$ENV_FILE" \
    -f "$COMPOSE_FILE" \
    "$@"
}

wait_until_ready() {
  local container health_status start_time
  start_time="$(date +%s)"
  while true; do
    container="$(compose ps -q app || true)"
    health_status=""
    if [ -n "$container" ]; then
      health_status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$container")"
    fi
    if [ "$health_status" = healthy ]; then
      return 0
    fi
    if [ $(( $(date +%s) - start_time )) -ge "$HEALTH_TIMEOUT_SECONDS" ]; then
      return 1
    fi
    sleep 5
  done
}

current_container="$(compose ps -q app || true)"
current_image=""
if [ -n "$current_container" ]; then
  current_image="$(docker inspect --format '{{.Config.Image}}' "$current_container")"
  if [[ ! "$current_image" =~ $IMAGE_PATTERN ]]; then
    echo "current app image is not a compatible Nuecagram version or digest" >&2
    exit 1
  fi
fi

if [ "$mode" = rollback ] && [ "$image" = previous ]; then
  if [ ! -s "$STATE_FILE" ]; then
    echo "No previous image recorded for rollback" >&2
    exit 1
  fi
  image="$(tr -d '[:space:]' < "$STATE_FILE")"
  if [[ ! "$image" =~ $IMAGE_PATTERN ]]; then
    echo "Recorded rollback image is invalid" >&2
    exit 1
  fi
fi

if [ -n "$current_image" ] && [ "$current_image" != "$image" ]; then
  printf '%s\n' "$current_image" > "$STATE_FILE.tmp"
  mv "$STATE_FILE.tmp" "$STATE_FILE"
fi

export NUECAGRAM_IMAGE="$image"
if compose pull app && compose up -d app && wait_until_ready; then
  printf 'Deployed %s\n' "$image"
  exit 0
fi

echo "Deployment failed readiness; restoring the previous image" >&2
if [ -n "$current_image" ]; then
  export NUECAGRAM_IMAGE="$current_image"
  if compose up -d app && wait_until_ready; then
    echo "Restored $current_image" >&2
  else
    echo "Automatic restore failed; manual recovery required" >&2
  fi
fi
exit 1
