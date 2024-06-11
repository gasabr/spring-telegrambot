package me.gasabr.telegram

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
    fromApplication<SpringTelegramBotApplication>().with(TestcontainersConfiguration::class).run(*args)
}
