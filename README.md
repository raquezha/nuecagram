# Nuecagram

Nuecagram simplifies GitLab webhook handling, delivering notifications directly to your Telegram

# Getting Started
## 1. Identify Your Telegram Group Chat

To integrate Nuecagram with Telegram, you'll need to identify your group chat and obtain its ID. Here's how:

- **Add [Nuecagram Bot](https://t.me/NuecagramBot) to Your Group Chat:**
    - Open your Telegram group chat.
    - Search for and add Nuecagram Bot to the group.

- **Get Your Telegram Group ID:**
    - Follow Telegram's guide to [obtain your group's chat ID](https://core.telegram.org/bots/api#getting-updates).
    - This ID is required for configuring Nuecagram.

- **Optional: Enable Topics (Thread ID):**
    - If you use topics within your Telegram group, and want the notification to be sent there note down the topic ID.

## 2. Configure GitLab Webhook
### GitLab Webhook Configuration
Set up a webhook in your GitLab repository to trigger events in Nuecagram:

1. **Navigate to Webhook Settings:**
    - Go to your GitLab repository.
    - Access **Settings > Webhooks**.

2. **Add New Webhook:**
    - Click on **Add Webhook**.

3. **Configure Webhook Details:**

    - **URL:** Use the following endpoint for Nuecagram:
      ```
      https://nuecagram.raquezha.net/webhook
      ```
    - **Custom Headers:**
      ```
      X-Nuecagram-Token: [Message me on telegram @raquezha for the token]
      X-Nuecagram-Chat-Id: [Telegram group chat ID]
      X-Nuecagram-Topic-Id (optional): [Topic or thread ID within Telegram]
      ```
    - **Name and Description:** (Optional) Provide a name and description for your webhook.

4. **Select Events to Trigger:**
    - Choose specific GitLab events that should trigger the webhook:
        - Tag Push Events
        - Comments
        - Issues Events
        - Merge Request Events
        - Job Events
        - Pipeline Events
        - Wiki Page Events
        - Deployment Events
        - Releases Events

   **Currently Not Supported Events:**
    - Project or Group Access Token Events
    - Emoji Events

5. **Test Your Webhook:**
    - Use the test button at the bottom to ensure your webhook is correctly configured and functional.

### Automate GitLab Webhook Configuration

You can automate the setup of the GitLab webhook using the provided script. Follow these steps:

1. **Navigate to the `scripts` Folder:**
    - Locate the `setup-webhook.sh` script in your repository's `scripts` folder.

2. **Modify Script Values:**
    - Open `setup-webhook.sh` and modify the following variables:
        - `PROJECT_ID`: Your GitLab project ID.
        - `WEBHOOK_URL`: URL where Nuecagram is deployed (`http://nuecagram.com/webhook`).
        - `PRIVATE_TOKEN`: Your GitLab private token for authentication.
        - `WEBHOOK_NAME`: Name for your Nuecagram webhook.
        - `WEBHOOK_DESCRIPTION`: Description for your Nuecagram webhook.
        - `SECRET_TOKEN`: Secret token for payload validation.
        - `X-Nuecagram-Chat-Id`: Your Telegram group chat ID.
        - `X-Nuecagram-Topic-Id`: (Optional) Topic or thread ID within Telegram.

3. **Make the Script Executable:**
    - If not already executable, run `chmod +x setup-webhook.sh` to make the script executable.

4. **Run the Script:**
    - Execute the script by running `./scripts/setup-webhook.sh`.
    - The script will make a POST request to GitLab's API to create the webhook with the specified configurations.

5. **Test Your Webhook:**

# How to host Nuecagram on your own server (using your private bots)

## 🐳 Docker way

### Prerequisites :
- You will need [docker](https://docs.docker.com/install/) and [docker-compose](https://docs.docker.com/compose/install/) installed
- Create your Telegram bot(s) by talking to [@BotFather](https://t.me/botfather)

### Clone the Repository

```bash
git clone https://github.com/raquezha/nuecagram.git
cd nuecagram
```

### Set Environment Variables

Ensure you have Docker installed and set the required environment variables in your Docker setup:

```bash
export TELEGRAM_BOT_TOKEN=yourtoken
export NUECAGRAM_SECRET_TOKEN=yourcustomtoken
```

If planning to use Docker Compose instead, edit the environment variables in `compose.yaml` starting at line 16.

### Build and Run the Docker Image

Use Docker to build and run the Nuecagram application:

```bash
docker build -t nuecagram .
docker run -d -e TELEGRAM_BOT_TOKEN=$TELEGRAM_BOT_TOKEN -e NUECAGRAM_SECRET_TOKEN=$NUECAGRAM_SECRET_TOKEN -p 8080:80 nuecagram
```

Docker Compose:
```bash
docker compose build
docker compose up
```

### Configure GitLab Webhook

Configure your GitLab project's webhook to send events to the endpoint provided by Nuecagram. Ensure to include the required custom headers for validation.


Take note of this required custom header when you add new webhook!

```
Custom Headers:
X-Nuecagram-Token: [Secret token for payload validation]
X-Nuecagram-Chat-Id: [Telegram group chat ID]
X-Nuecagram-Topic-Id (optional): [Topic or thread ID within Telegram]
```
