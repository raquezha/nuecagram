# Webhook scripts

The helper scripts call GitLab project hooks API. Provide the GitLab personal access token through GITLAB_PAT; the token is never stored in the script.

## Create, list, or batch update hooks

```bash
GITLAB_PAT=... scripts/create_gitlab_webhook.sh https://gitlab.com PROJECT_ID https://example.com/nuecagram/webhook GITLAB_WEBHOOK_KEY
GITLAB_PAT=... scripts/get_gitlab_webhook.sh https://gitlab.com PROJECT_ID
```

### Batch update webhooks across projects

To migrate all project webhooks matching an old domain (e.g. `raquezha.net`) to your new endpoint:

```bash
GITLAB_PAT=your_pat
NEW_URL="https://new-domain.com/nuecagram/webhook"
NEW_KEY="new_webhook_secret"

for pid in $(curl -s --header "PRIVATE-TOKEN: $GITLAB_PAT" "https://gitlab.com/api/v4/projects?membership=true&per_page=100" | jq -r '.[].id'); do
  hooks=$(curl -s --header "PRIVATE-TOKEN: $GITLAB_PAT" "https://gitlab.com/api/v4/projects/$pid/hooks")
  for hid in $(echo "$hooks" | jq -r '.[] | select(.url | contains("nuecagram")) | .id'); do
    echo "Updating project $pid hook $hid..."
    curl -s --request PUT "https://gitlab.com/api/v4/projects/$pid/hooks/$hid" \
      --header "PRIVATE-TOKEN: $GITLAB_PAT" \
      --data-urlencode "url=$NEW_URL" \
      --data "token=$NEW_KEY" > /dev/null
  done
done
```

The create script uses GitLab native token field. GitLab sends it back to Nuecagram as X-Gitlab-Token. No custom Nuecagram headers are created.
