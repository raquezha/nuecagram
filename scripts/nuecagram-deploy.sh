#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 --mode <deploy|rollback> --image <image-ref|previous> --project-root <path> --env-file <path> --compose-file <path> --state-file <path> --health-url <url> --timeout-seconds <seconds>" >&2
}

require_value() {
  if [ -z "${2:-}" ]; then
    echo "Missing $1" >&2
    usage
    exit 1
  fi
}

mode=""
image=""
project_root=""
env_file=""
compose_file=""
state_file=""
health_url=""
timeout_seconds=""

while [ $# -gt 0 ]; do
  case "$1" in
    --mode) mode="${2:-}"; shift 2 ;;
    --image) image="${2:-}"; shift 2 ;;
    --project-root) project_root="${2:-}"; shift 2 ;;
    --env-file) env_file="${2:-}"; shift 2 ;;
    --compose-file) compose_file="${2:-}"; shift 2 ;;
    --state-file) state_file="${2:-}"; shift 2 ;;
    --health-url) health_url="${2:-}"; shift 2 ;;
    --timeout-seconds) timeout_seconds="${2:-}"; shift 2 ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

require_value mode "$mode"
require_value image "$image"
require_value project-root "$project_root"
require_value env-file "$env_file"
require_value compose-file "$compose_file"
require_value state-file "$state_file"
require_value health-url "$health_url"
require_value timeout-seconds "$timeout_seconds"

if [ "$mode" != deploy ] && [ "$mode" != rollback ]; then
  echo "mode must be deploy or rollback" >&2
  exit 1
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "docker missing" >&2
  exit 1
fi

mkdir -p "$(dirname "$state_file")"

compose() {
  docker compose \
    --project-directory "$project_root" \
    --env-file "$env_file" \
    -f "$compose_file" \
    "$@"
}

current_container="$(compose ps -q app || true)"
current_image=""
if [ -n "$current_container" ]; then
  current_image="$(docker inspect --format '{{.Config.Image}}' "$current_container")"
fi

if [ "$mode" = rollback ] && [ "$image" = previous ]; then
  if [ ! -s "$state_file" ]; then
    echo "No previous image recorded for rollback" >&2
    exit 1
  fi
  image="$(tr -d '[:space:]' < "$state_file")"
fi

if [ "$mode" = deploy ] && [ -n "$current_image" ] && [ "$current_image" != "$image" ]; then
  printf '%s\n' "$current_image" > "$state_file"
fi

export NUECAGRAM_IMAGE="$image"
compose pull app
compose up -d app

start_time="$(date +%s)"
while true; do
  if curl --fail --silent --show-error "$health_url" >/dev/null; then
    break
  fi
  if [ $(( $(date +%s) - start_time )) -ge "$timeout_seconds" ]; then
    echo "Readiness check failed for $health_url" >&2
    exit 1
  fi
  sleep 5
done

printf 'Deployed %s\n' "$image"
