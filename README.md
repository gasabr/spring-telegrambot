# Spring Telegram Bot Example

## How to run the bot using long polling

```shell
TELEGRAM_API_TOKEN=YOUR_TOKEN ./gradlew bootRun
```

## How to build docker image

```shell
./gradlew bootBuildImage
# image name will look something like 'docker.io/library/spring-telegrambot:0.0.1-SNAPSHOT'
```