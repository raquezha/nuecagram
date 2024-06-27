#!/bin/bash

GITLAB_URL="https://gitlab.com"
PROJECT_ID="your_project_id"
PRIVATE_TOKEN="your_private_token"

# Make the POST request to create the webhook
curl --request GET "$GITLAB_URL/api/v4/projects/$PROJECT_ID/hooks" \
     --header "PRIVATE-TOKEN: $PRIVATE_TOKEN" \
     --header "Content-Type: application/json"
