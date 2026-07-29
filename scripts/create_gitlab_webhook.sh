#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: GITLAB_PAT=... $0 <gitlab-url> <project-id> <webhook-url> <gitlab-webhook-key>" >&2
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
WEBHOOK_URL="${3:-}"
WEBHOOK_KEY="${4:-}"
WEBHOOK_NAME="${WEBHOOK_NAME:-Nuecagram Webhook}"
WEBHOOK_DESCRIPTION="${WEBHOOK_DESCRIPTION:-Nuecagram GitLab notifications}"

require "gitlab-url" "$GITLAB_URL"
require "project-id" "$PROJECT_ID"
require "webhook-url" "$WEBHOOK_URL"
require "gitlab-webhook-key" "$WEBHOOK_KEY"
require "GITLAB_PAT" "${GITLAB_PAT:-}"

curl --fail --show-error --silent --request POST \
  "${GITLAB_URL%/}/api/v4/projects/${PROJECT_ID}/hooks" \
  --header "PRIVATE-TOKEN: ${GITLAB_PAT}" \
  --data-urlencode "url=${WEBHOOK_URL}" \
  --data-urlencode "name=${WEBHOOK_NAME}" \
  --data-urlencode "description=${WEBHOOK_DESCRIPTION}" \
  --data "token=${WEBHOOK_KEY}" \
  --data "enable_ssl_verification=true" \
  --data "pipeline_events=true" \
  --data "push_events=true" \
  --data "tag_push_events=true" \
  --data "merge_requests_events=true" \
  --data "issues_events=true" \
  --data "note_events=true" \
  --data "confidential_issues_events=true" \
  --data "confidential_note_events=true" \
  --data "deployment_events=true" \
  --data "releases_events=true" \
  --data "job_events=true" \
  --data "wiki_page_events=false" \
  --data "resource_access_token_events=false"

echo
