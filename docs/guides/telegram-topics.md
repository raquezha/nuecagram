# Telegram Forum Topic Routing

Nuecagram supports Telegram Supergroup Forum Topics out of the box.

## How Topic Routing Works

1. **Setup in Topic**: Run `/setup <gitlab-url> <project-id>` inside a specific Telegram topic.
2. **Topic Binding**: Nuecagram stores `telegramTopicId` alongside `telegramChatId` in PostgreSQL.
3. **Targeted Delivery**: Webhooks for that project post directly into that specific topic (`messageThreadId`) without touching General or other topics.
4. **Preserved Command Replies**: Slash commands typed in a topic reply inside that same topic.
