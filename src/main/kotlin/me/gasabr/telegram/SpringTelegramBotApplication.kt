package me.gasabr.telegram

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.UpdatesListener
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.request.SendMessage
import io.micrometer.common.util.internal.logging.Slf4JLoggerFactory
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.support.GenericMessage
import org.springframework.statemachine.StateContext
import org.springframework.statemachine.StateMachine
import org.springframework.statemachine.action.Action
import org.springframework.statemachine.config.EnableStateMachineFactory
import org.springframework.statemachine.config.StateMachineConfigurerAdapter
import org.springframework.statemachine.config.StateMachineFactory
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
    private val stateMachineFactory: StateMachineFactory<States, Events>,
) {

    private val conversationStates: MutableMap<Long, StateMachine<States, Events>> = mutableMapOf()

    companion object {
        val logger = LoggerFactory.getLogger(TelegramBotSetup::class.java)!!
    }

    @PostConstruct
    fun configureBot() {
        logger.info("Configuring telegram bot to use pulling.")

        telegramBot.setUpdatesListener {
            logger.info("Got some updates.")
            it.forEach { upd ->
                val smKey = upd.replyChatId()
                val sm = conversationStates.computeIfAbsent(smKey) { key ->
                    return@computeIfAbsent stateMachineFactory.getStateMachine(key.toString())
                }

                // fixme: how does this work, if I have not yet persisted anything?
                sm.startReactively().block()
                sm.extendedState.variables["update"] = upd
                logger.debug("Currently state machine is in `${sm.state.id}` state.")
                sm.sendEvent(Mono.just(GenericMessage(Events.GOT_TEXT))).blockFirst()
                logger.debug("After handling event state machine is in `${sm.state.id}` state.")

                if (sm.state.id == States.CONVERSATION_ENDED) {
                    // removing sm from memory once we got to the terminal state
                    conversationStates.remove(smKey)
                }
            }

            return@setUpdatesListener UpdatesListener.CONFIRMED_UPDATES_ALL
        }
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
    SENT_RESPONSE,
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
    telegramBot.execute(SendMessage(update.replyChatId(), "okay, ${name}!"))
}

fun sendEcho(stateContext: StateContext<States, Events>, telegramBot: TelegramBot) {
    val update = getUpdateFromContext(stateContext)
    val textWithoutCommand = update.message()
        .text()
        .split(" ")
        .drop(1)
        .joinToString(" ")
    telegramBot.execute(SendMessage(update.replyChatId(), textWithoutCommand))
}

fun sendError(stateContext: StateContext<States, Events>, telegramBot: TelegramBot, errorMessage: String) {
    val update = getUpdateFromContext(stateContext)
    telegramBot.execute(SendMessage(update.replyChatId(), errorMessage))
}

@Configuration
@EnableStateMachineFactory
class StateMachineConfig(
    private val telegramBot: TelegramBot,
) : StateMachineConfigurerAdapter<States, Events>() {

    override fun configure(states: StateMachineStateConfigurer<States, Events>) {
        super.configure(states)
        states.withStates()
            .initial(States.IDLE)
            .choice(States.GOT_CMD)
            .states(States.entries.toTypedArray().toMutableSet())
            .end(States.CONVERSATION_ENDED)
            .and()
            .withStates()
            .parent(States.GOT_CMD)
            .initial(States.GOT_HELLO_CMD)
            .stateEntry(States.GOT_HELLO_CMD, CallbackAction { c ->
                sendNamePrompt(c, telegramBot)
            })
            .state(States.GOT_NAME, CallbackAction { c ->
                sendHelloWorld(c, telegramBot)
            })
            .and()
            .withStates()
            .parent(States.GOT_CMD)
            .initial(States.GOT_ANOTHER_CMD)
            .stateEntry(States.GOT_ANOTHER_CMD, CallbackAction { context ->
                sendEcho(context, telegramBot)
            })
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
            .last(States.IDLE, CallbackAction { ctx -> sendError(ctx, telegramBot, "Can not parse command.") })
            .and()
            // fixme: this should be internal transition or something
            .withExternal()
            .source(States.GOT_ANOTHER_CMD)
            .event(Events.SENT_RESPONSE)
            .target(States.CONVERSATION_ENDED)
    }
}

/**
 * @param errorEvent -- in case of error when running callback, action will send `errorEvent` to the State Machine.
 */
class CallbackAction(
    private val errorEvent: Events = Events.GOT_INVALID_INPUT,
    private val callback: (StateContext<States, Events>) -> Unit,
) : Action<States, Events> {
    companion object {
        private val logger = Slf4JLoggerFactory.getInstance(CallbackAction::class.java)
    }

    override fun execute(context: StateContext<States, Events>) {
        try {
            callback(context)
        } catch (e: Exception) {
            logger.error("Got error executing callback: ${e.message}")
            context.stateMachine.sendEvent(Mono.just(GenericMessage(errorEvent))).blockFirst()
        }
        context.stateMachine.sendEvent(Mono.just(GenericMessage(Events.SENT_RESPONSE))).blockFirst()
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
