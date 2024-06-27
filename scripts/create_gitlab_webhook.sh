#!/bin/bash

# Variables (replace these with your actual values)
GITLAB_URL="https://gitlab.com"
PROJECT_ID="XXX"
WEBHOOK_URL="https://15a8-158-62-83-135.ngrok-free.app/webhook"
PRIVATE_TOKEN="your_private_token"
WEBHOOK_NAME="Nuecagram Webhook"
WEBHOOK_DESCRIPTION="Nuecagram made by raquezha"
SECRET_TOKEN=""

# Make the POST request to create the webhook
curl --request POST "$GITLAB_URL/api/v4/projects/$PROJECT_ID/hooks" \
     --header "PRIVATE-TOKEN: $PRIVATE_TOKEN" \
     --header "Content-Type: application/json" \
     --data '{
  "url": "'"$WEBHOOK_URL"'",
  "name": "'"$WEBHOOK_NAME"'",
  "description": "'"$WEBHOOK_DESCRIPTION"'",
  "confidential_issues_events": true,
  "confidential_note_events": true,
  "deployment_events": true,
  "enable_ssl_verification": true,
  "issues_events": true,
  "job_events": true,
  "merge_requests_events": true,
  "note_events": true,
  "pipeline_events": true,
  "push_events_branch_filter": "",
  "push_events": true,
  "releases_events": true,
  "tag_push_events": true,
  "token": "'"$SECRET_TOKEN"'",
  "wiki_page_events": false,
  "resource_access_token_events": false,
  "custom_webhook_template": "",
  "custom_headers": [
    { "key": "X-Nuecagram-Token", "value": "your_private_token" },
    { "key": "X-Nuecagram-Chat-Id", "value": "your_private_token" },
    { "key": "X-Nuecagram-Topic-Id", "value": "your_private_token" }
  ]
}'
