# Webhook scripts

The helper scripts call GitLab project hooks API. Provide the GitLab personal access token through GITLAB_PAT; the token is never stored in the script.

## Create or list hooks

```bash
GITLAB_PAT=... scripts/create_gitlab_webhook.sh https://gitlab.com PROJECT_ID https://example.com/nuecagram/webhook GITLAB_WEBHOOK_KEY
GITLAB_PAT=... scripts/get_gitlab_webhook.sh https://gitlab.com PROJECT_ID
```

The create script uses GitLab native token field. GitLab sends it back to Nuecagram as X-Gitlab-Token. No custom Nuecagram headers are created.
