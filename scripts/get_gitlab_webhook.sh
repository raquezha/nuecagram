#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: GITLAB_PAT=... $0 <gitlab-url> <project-id>" >&2
}

require() {
  if [ -z "${2:-}" ]; then
    echo "Missing $1" >&2
    usage
    exit 1
  fi
}

GITLAB_URL="${1:-}"
PROJECT_ID="${2:-}"

require "gitlab-url" "$GITLAB_URL"
require "project-id" "$PROJECT_ID"
require "GITLAB_PAT" "${GITLAB_PAT:-}"

curl --fail --show-error --silent --request GET \
  "${GITLAB_URL%/}/api/v4/projects/${PROJECT_ID}/hooks" \
  --header "PRIVATE-TOKEN: ${GITLAB_PAT}"

echo
