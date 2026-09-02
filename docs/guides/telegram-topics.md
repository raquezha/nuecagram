# Telegram Forum Topic Routing

Nuecagram supports Telegram Supergroup Forum Topics out of the box.

## How Topic Routing Works

1. **Setup in Web App**: In the Web App portal, select your target Telegram group and topic from the destination picker when adding a repository.
2. **Topic Binding**: Nuecagram stores `telegramTopicId` alongside `telegramChatId` in PostgreSQL.
3. **Targeted Delivery**: Webhooks for that project post directly into that specific topic (`messageThreadId`) without touching General or other topics.
4. **Preserved Command Replies**: Slash commands typed in a topic reply inside that same topic.
