# MinecraftAI

**Automatic AI-Powered Chat Moderation for Minecraft**

## Description

ChatAIPlugin is a Bukkit/Spigot plugin that uses the OpenAI API to automatically moderate player chat. It scans messages in real-time and blocks messages that are toxic, abusive, or inappropriate. This plugin is perfect for servers that want to maintain a safe and friendly environment without manual moderation.

## Features

- Automatic scanning of player messages
- Blocking overly long messages
- Blocking messages containing forbidden commands (e.g., `/prompt`)
- Configurable daily message limits and maximum message length
- Optional automatic deletion of flagged messages
- Logging of blocked messages to the server console
- Asynchronous processing to reduce server lag

## Installation

1. Copy `ChatAIPlugin.jar` to the `plugins` folder of your Bukkit/Spigot server.
2. Start the server to generate the default `config.yml`.
3. Edit `config.yml` and add your OpenAI API key and configure limits:
```yaml
openai_api_key: "YOUR_OPENAI_API_KEY"
max_messages_per_day: 1000
max_message_length: 200
delete_bad_messages: true
scan_every_x_messages: 5
```
4. Restart the server.

## Configuration

- `openai_api_key` – Your OpenAI API key
- `max_messages_per_day` – Maximum number of messages scanned per day
- `max_message_length` – Maximum message length; messages longer than this are blocked
- `delete_bad_messages` – Whether to automatically delete messages flagged as bad
- `scan_every_x_messages` – Number of messages after which the queue is processed

## How It Works

1. The plugin listens to `AsyncPlayerChatEvent`.
2. Messages are added to a queue.
3. Once `scan_every_x_messages` is reached, the queue is processed asynchronously.
4. Messages are sent to the OpenAI API (GPT-3.5-turbo) to determine if they are toxic or inappropriate.
5. If a message is flagged as "bad" and `delete_bad_messages` is enabled, it is blocked and the player receives a notification.

## Requirements

- Minecraft: Bukkit/Spigot/Paper
- Java 8+
- OpenAI API Key

## License

This project is licensed under the **GNU General Public License v3.0 (GPLv3)**. You are free to use, modify, and redistribute this software under the terms of the GPLv3.

For the full license text, see [LICENSE](LICENSE).
