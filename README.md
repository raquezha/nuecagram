# Nuecagram

Nuecagram simplifies GitLab webhook handling, delivering notifications directly to your Telegram.

## Features

- **Real-time GitLab notifications** - Get instant Telegram alerts for pushes, merge requests, issues, pipelines, and more
- **Pipeline message consolidation** - Multiple pipeline/job events are consolidated into a single updating message
- **Topic support** - Send notifications to specific Telegram group topics/threads
- **Self-hostable** - Deploy your own instance with your private Telegram bot

## Supported GitLab Events

| Event Type | Status | Notes |
|------------|--------|-------|
| Pipeline Events | Supported | **Recommended** - consolidated updating messages |
| Push Events | Supported | |
| Tag Push Events | Supported | |
| Merge Request Events | Supported | |
| Issue Events | Supported | |
| Comments (Notes) | Supported | |
| Wiki Page Events | Supported | |
| Deployment Events | Supported | |
| Release Events | Supported | |
| Job Events | Supported | Not recommended - use Pipeline Events instead |
| Project/Group Access Token Events | Not supported | |
| Emoji Events | Not supported | |

## Getting Started

### 1. Identify Your Telegram Group Chat

To integrate Nuecagram with Telegram, you'll need to identify your group chat and obtain its ID:

- **Add [Nuecagram Bot](https://t.me/NuecagramBot) to Your Group Chat:**
    - Open your Telegram group chat
    - Search for and add Nuecagram Bot to the group

- **Get Your Telegram Group ID:**
    - Follow Telegram's guide to [obtain your group's chat ID](https://core.telegram.org/bots/api#getting-updates)
    - This ID is required for configuring Nuecagram

- **Optional: Enable Topics (Thread ID):**
    - If you use topics within your Telegram group, note down the topic ID

### 2. Configure GitLab Webhook

Set up a webhook in your GitLab repository to trigger events in Nuecagram:

1. **Navigate to Webhook Settings:**
    - Go to your GitLab repository
    - Access **Settings > Webhooks**

2. **Add New Webhook:**
    - Click on **Add Webhook**

3. **Configure Webhook Details:**

    - **URL:**
      ```
      https://nuecagram.raquezha.net/webhook
      ```
    - **Custom Headers:**
      ```
      X-Nuecagram-Token: [Message me on telegram @raquezha for the token]
      X-Nuecagram-Chat-Id: [Telegram group chat ID]
      X-Nuecagram-Topic-Id (optional): [Topic or thread ID within Telegram]
      ```
    - **Name and Description:** (Optional) Provide a name and description for your webhook

4. **Select Events to Trigger:**

    > **Tip:** For best pipeline/job notifications, enable **Pipeline Events**. 
    > Pipeline events contain complete job information and produce consolidated, 
    > updating messages.

    **Recommended events:**
    - **Pipeline Events** (recommended for pipeline/job notifications)
    - Push Events
    - Tag Push Events
    - Comments
    - Issues Events
    - Merge Request Events
    - Wiki Page Events
    - Deployment Events
    - Releases Events
    
    **Not recommended:**
    - Job Events - Use Pipeline Events instead for better consolidated messages. If both are enabled, Job Events are automatically ignored to prevent duplicates.

5. **Test Your Webhook:**
    - Use the test button to ensure your webhook is correctly configured

### Automate GitLab Webhook Configuration

You can automate the setup using the provided script:

1. Navigate to the `scripts` folder and locate `create_gitlab_webhook.sh`

2. Modify the script variables:
    - `PROJECT_ID`: Your GitLab project ID
    - `WEBHOOK_URL`: URL where Nuecagram is deployed
    - `PRIVATE_TOKEN`: Your GitLab private token
    - `WEBHOOK_NAME`: Name for your webhook
    - `SECRET_TOKEN`: Secret token for payload validation
    - `X-Nuecagram-Chat-Id`: Your Telegram group chat ID
    - `X-Nuecagram-Topic-Id`: (Optional) Topic ID

3. Make executable and run:
    ```bash
    chmod +x scripts/create_gitlab_webhook.sh
    ./scripts/create_gitlab_webhook.sh
    ```

## Self-Hosting

### Prerequisites
- [Docker](https://docs.docker.com/install/) and [docker-compose](https://docs.docker.com/compose/install/)
- Telegram bot token from [@BotFather](https://t.me/botfather)

### Quick Start

```bash
# Clone the repository
git clone https://github.com/raquezha/nuecagram.git
cd nuecagram

# Set environment variables
export TELEGRAM_BOT_TOKEN=yourtoken
export NUECAGRAM_SECRET_TOKEN=yourcustomtoken

# Build and run with Docker
docker build -t nuecagram .
docker run -d \
  -e TELEGRAM_BOT_TOKEN=$TELEGRAM_BOT_TOKEN \
  -e NUECAGRAM_SECRET_TOKEN=$NUECAGRAM_SECRET_TOKEN \
  -p 8080:80 \
  nuecagram
```

### Using Docker Compose

Edit environment variables in `compose.yaml`, then:

```bash
docker compose build
docker compose up -d
```

### Configure GitLab Webhook for Self-Hosted Instance

When adding a webhook, use these custom headers:

```
X-Nuecagram-Token: [Your secret token for payload validation]
X-Nuecagram-Chat-Id: [Telegram group chat ID]
X-Nuecagram-Topic-Id: [Optional - Topic or thread ID]
```

## Development

### Build Commands

```bash
./gradlew build              # Build + lint + test
./gradlew clean build        # Clean build
./gradlew test               # Run tests only
./gradlew run                # Run application
./gradlew lintKotlin         # Lint only
./gradlew formatKotlin       # Auto-format code
```

### Tech Stack

- **Language:** Kotlin 1.9.24
- **Framework:** Ktor (server + client)
- **DI:** Koin with annotations
- **Telegram:** vendeli telegram-bot library
- **GitLab:** gitlab4j-api for event parsing
- **Testing:** JUnit4, MockK, Google Truth

## License

MIT License - see [LICENSE](LICENSE) for details.
