import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity
import dev.kord.common.Color
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.respondEphemeral
import dev.kord.core.event.gateway.ReadyEvent
import dev.kord.core.event.interaction.GuildButtonInteractionCreateEvent
import dev.kord.core.event.interaction.GuildChatInputCommandInteractionCreateEvent
import dev.kord.core.event.user.VoiceStateUpdateEvent
import dev.kord.core.on
import dev.kord.gateway.Intent
import dev.kord.rest.builder.interaction.string
import dev.kord.rest.builder.message.create.InteractionResponseCreateBuilder
import dev.kord.rest.builder.message.create.embed
import kotlinx.coroutines.*
import mu.KotlinLogging
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

suspend fun main(args: Array<String>) {
    val lava = DefaultAudioPlayerManager()
    AudioSourceManagers.registerRemoteSources(lava)

    val sessions: MutableMap<Snowflake, InteractiveSession> = mutableMapOf()

    val token = args[0]
    val kord = Kord(token) {
        enableShutdownHook = false
    }

    Runtime.getRuntime().addShutdownHook(thread(false) {
        runBlocking {
            sessions.values
                .map {
                    async {
                        runCatching {
                            it.disconnect()
                            it.done.receive()
                        }
                    }
                }
                .awaitAll()

            kord.shutdown()
        }
    })

    kord.createGlobalChatInputCommand("play", "Plays audio on your voice channel") {
        string("url", "Media to play")
    }

    kord.on<ReadyEvent> {
        logger.info { "Bot is ready!" }
    }

    kord.on<VoiceStateUpdateEvent> {
        if (state.userId != kord.selfId || state.channelId != null) {
            return@on
        }

        val session = sessions.remove(state.guildId) ?: return@on
        session.disconnect()
    }

    kord.on<GuildChatInputCommandInteractionCreateEvent> {
        play(lava, sessions)
    }

    kord.on<GuildButtonInteractionCreateEvent> {
        val session = sessions[interaction.guildId] ?: return@on

        session.handleButton(interaction.componentId)

        interaction.respondEphemeral { }
    }

    kord.on<VoiceStateUpdateEvent> {
        val channel = state.getChannelOrNull()
            ?: old?.getChannelOrNull()
            ?: return@on

        val session = sessions[channel.guildId] ?: return@on

        session.handleVoiceState(state)
    }

    kord.login {
        intents += Intent.GuildVoiceStates
    }
}

private suspend fun GuildChatInputCommandInteractionCreateEvent.play(
    lava: DefaultAudioPlayerManager,
    sessions: MutableMap<Snowflake, InteractiveSession>
) {
    val ownVoiceChannel = interaction.guild.getMember(kord.selfId).getVoiceStateOrNull()?.channelId
    val requestedVoiceChannel = interaction.user.getVoiceStateOrNull()?.getChannelOrNull().otherwise {
        interaction.respondEphemeral { content = "You must be in a voice channel" }
        return
    }

    val response = StatefulResponse.from(interaction.deferPublicResponse())

    val tracks = try {
        withTimeout(10.seconds) {
            val fromUrl = interaction.command.strings.values
                .flatMap { url -> lava.loadItem(url) }
            val fromAttachment = interaction.command.attachments.values
                .flatMap { file -> lava.loadItem(file.url) }

            (fromUrl + fromAttachment)
        }
    } catch (ex: FriendlyException) {
        interaction.respondEphemeral {
            displayException(ex, ex.severity)
        }

        logger.error { ex.stackTraceToString() }
        return
    } catch (ex: Exception) {
        interaction.respondEphemeral {
            displayException(ex, Severity.FAULT)
        }

        logger.error { ex.stackTraceToString() }
        return
    }

    if (tracks.isEmpty()) {
        interaction.respondEphemeral { content = "No matches" }
    }

    val session = sessions.replace(interaction.guildId) { existing ->
        if (existing == null || ownVoiceChannel != requestedVoiceChannel.id) {
            val session = InteractiveSession.fromOriginalInteraction(
                lava.createPlayer(),
                requestedVoiceChannel,
                response.consume()
            )

            interaction.kord.launch {
                session.done.receiveCatching()
                sessions.remove(interaction.guildId, session)
            }

            session
        } else {
            existing
        }
    }

    tracks.forEach {
        session.enqueue(it, interaction.user)
    }

    response.delete()
}

private fun InteractionResponseCreateBuilder.displayException(exception: Exception, severity: Severity) {
    embed {
        title = "Error"
        description = exception.message

        color = when (severity) {
            Severity.COMMON -> null
            Severity.SUSPICIOUS -> Color(190, 145, 23)
            Severity.FAULT -> Color(199, 84, 80)
        }
    }
}