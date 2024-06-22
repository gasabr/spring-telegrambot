# Spring Telegram Bot Example

Bot does not persist the state to add persistence, please, refer to [spring documentation](https://docs.spring.io/spring-statemachine/docs/current/reference/#sm-persist), it mixes up reactive
programming with blocking paradigm for this example i don't care. It is a single file all in one example at the end
of the day, I will update it with proper structure and other goodies like classes for building conversations in
the future

## How to run the bot using long polling

```shell
TELEGRAM_API_TOKEN=YOUR_TOKEN ./gradlew bootRun
```

## How to build docker image

```shell
./gradlew bootBuildImage
# image name will look something like 'docker.io/library/spring-telegrambot:0.0.1-SNAPSHOT'
```