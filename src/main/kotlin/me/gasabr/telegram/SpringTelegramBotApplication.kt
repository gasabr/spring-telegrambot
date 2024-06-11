package me.gasabr.telegram

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.UpdatesListener
import com.pengrad.telegrambot.model.File
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.request.SetWebhook
import io.micrometer.common.util.internal.logging.Slf4JLoggerFactory
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.ConstructorBinding
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.support.GenericMessage
import org.springframework.statemachine.StateContext
import org.springframework.statemachine.StateMachine
import org.springframework.statemachine.action.Action
import org.springframework.statemachine.config.EnableStateMachine
import org.springframework.statemachine.config.StateMachineConfigurerAdapter
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer
import org.springframework.statemachine.guard.Guard
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/// main function is at the bottom of the file
@SpringBootApplication
class SpringTelegramBotApplication

@Configuration
class TelegramConfiguration {
    @Bean
    fun telegramBot(@Value("\${telegram.api-token}") apiToken: String): TelegramBot = TelegramBot(apiToken)
}

@Component
class TelegramBotSetup(
    private val telegramBot: TelegramBot,
    private val stateMachine: StateMachine<States, Events>
) {

    companion object {
        val logger = LoggerFactory.getLogger(TelegramBotSetup::class.java)!!
    }

    @PostConstruct
    fun configureBot() {
        logger.info("Configuring telegram bot to use pulling.")

        telegramBot.setUpdatesListener {
            logger.info("Got some updates.")
            it.forEach { upd ->
                // fixme: how does this work, if I have not yet persisted anything?
                stateMachine.startReactively().block()
                stateMachine.extendedState.variables["update"] = upd
                logger.debug("Currently state machine is in `${stateMachine.state.id}` state.")
                stateMachine.sendEvent(Mono.just(GenericMessage(parseUpdateType(upd)))).blockFirst()
            }

            return@setUpdatesListener UpdatesListener.CONFIRMED_UPDATES_ALL
        }
    }

    @PostConstruct
    fun configureBotForWebhooks() {
        val request = SetWebhook()
            .url("")
            .certificate(java.io.File("filename"))
        telegramBot.execute(request)
    }

    private fun parseUpdateType(update: Update): Events {
        // fixme: parse the type properly here
        return Events.GOT_TEXT
    }
}

enum class States {
    IDLE,
    GOT_CMD,
    GOT_HELLO_CMD,
    GOT_ANOTHER_CMD,
    GOT_NAME,
    CONVERSATION_ENDED,
}


enum class Events {
    GOT_TEXT,
    GOT_INVALID_INPUT,
}

private fun getUpdateFromContext(stateContext: StateContext<States, Events>): Update {
    return stateContext.stateMachine
        .extendedState.variables.get("update") as Update?
        ?: throw RuntimeException("no update")
}

/**
 * Returns the id of the telegram chat to reply to.
 */
fun Update.replyChatId(): Long {
    return this.message().chat().id()
}

fun sendNamePrompt(stateContext: StateContext<States, Events>, telegramBot: TelegramBot) {
    val update = getUpdateFromContext(stateContext)
    telegramBot.execute(SendMessage(update.replyChatId(), "Hello! What is your name?"))
}

fun sendHelloWorld(stateContext: StateContext<States, Events>, telegramBot: TelegramBot) {
    val update = getUpdateFromContext(stateContext)
    val name = update.message().text()
    if (name != "Gleb") {
        throw RuntimeException("example exception.")
    } else {
        telegramBot.execute(SendMessage(update.replyChatId(), "okay, ${name}!"))
    }
}

@Configuration
@EnableStateMachine
class StateMachineConfig(
    private val telegramBot: TelegramBot,
) : StateMachineConfigurerAdapter<States, Events>() {

    override fun configure(states: StateMachineStateConfigurer<States, Events>) {
        super.configure(states)
        val x = {}
        states.withStates()
            .initial(States.IDLE)
            .choice(States.GOT_CMD)
            .states(States.entries.toTypedArray().toMutableSet())
            .and()
            .withStates()
            .parent(States.GOT_CMD)
            .initial(States.GOT_HELLO_CMD)
            .stateEntry(States.GOT_HELLO_CMD, CallbackAction(telegramBot) { c, b ->
                // do something with c & b
                sendNamePrompt(c, b)
            })
            .state(States.GOT_NAME, CallbackAction(telegramBot) { c, _ ->
                // do something with c & b
                sendHelloWorld(c, telegramBot)
            })
            .end(States.CONVERSATION_ENDED)
    }

    override fun configure(transitions: StateMachineTransitionConfigurer<States, Events>) {
        super.configure(transitions)
        transitions
            .withExternal()
            .source(States.IDLE)
            .event(Events.GOT_TEXT)
            .guard(AnyCommandGuard())
            .target(States.GOT_CMD)
            .and()
            .withExternal()
            .event(Events.GOT_TEXT)
            .source(States.GOT_HELLO_CMD)
            .target(States.GOT_NAME)
            .and()
            .withExternal()
            .event(Events.GOT_TEXT)
            .source(States.GOT_NAME)
            .target(States.CONVERSATION_ENDED)
            .and()
            .withExternal()
            .event(Events.GOT_INVALID_INPUT)
            .source(States.GOT_NAME)
            .target(States.GOT_HELLO_CMD)

            .and()
            .withChoice()
            .source(States.GOT_CMD)
            .first(States.GOT_HELLO_CMD, CommandGuard("hello"))
            .then(States.GOT_ANOTHER_CMD, CommandGuard("another"))
            .last(States.IDLE, CallbackAction(telegramBot) { c, b -> })

    }
}

/**
 * @param errorEvent -- in case of error when running callback, action will send `errorEvent` to the State Machine.
 */
class CallbackAction(
    // fixme: looks like I can simplify callback and not provide this argument
    private val telegramBot: TelegramBot,
    private val errorEvent: Events = Events.GOT_INVALID_INPUT,
    private val callback: (StateContext<States, Events>, TelegramBot) -> Unit,
) : Action<States, Events> {
    companion object {
        private val logger = Slf4JLoggerFactory.getInstance(CallbackAction::class.java)
    }

    override fun execute(context: StateContext<States, Events>) {
        try {
            callback(context, telegramBot)
        } catch (e: Exception) {
            logger.error("Error running callback: ${e.message}")
            context.stateMachine.sendEvent(Mono.just(GenericMessage(errorEvent))).blockFirst()
        }

    }
}

class CommandGuard(private val command: String) : Guard<States, Events> {
    override fun evaluate(context: StateContext<States, Events>): Boolean {
        return getUpdateFromContext(context).message().text().startsWith("/$command")
    }
}

class AnyCommandGuard : Guard<States, Events> {
    override fun evaluate(context: StateContext<States, Events>): Boolean {
        return getUpdateFromContext(context).message().text().startsWith("/")
    }
}

fun main(args: Array<String>) {
    runApplication<SpringTelegramBotApplication>(*args)
}
