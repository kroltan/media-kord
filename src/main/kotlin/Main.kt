import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
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
import dev.kord.rest.builder.message.create.embed
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

suspend fun main(args: Array<String>) {
    val lava = DefaultAudioPlayerManager()
    AudioSourceManagers.registerRemoteSources(lava)

    val sessions: MutableMap<Snowflake, InteractiveSession> = mutableMapOf()

    val bot_token = args[0];

    val kord = Kord(bot_token) {
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
        string("url", "Media to play") {
            required = true
        }
    }

    kord.on<ReadyEvent> {
        println("Bot is ready!")
    }

    kord.on<VoiceStateUpdateEvent> {
        if (state.userId != kord.selfId || state.channelId != null) {
            return@on
        }

        val session = sessions.remove(state.guildId) ?: return@on
        session.disconnect()
    }

    kord.on<GuildChatInputCommandInteractionCreateEvent> {
        val ownVoiceChannel = interaction.guild.getMember(kord.selfId).getVoiceStateOrNull()?.channelId
        val requestedVoiceChannel = interaction.user.getVoiceStateOrNull()?.getChannelOrNull().otherwise {
            interaction.respondEphemeral { content = "You must be in a voice channel" }
            return@on
        }

        val response = StatefulResponse.from(interaction.deferPublicResponse())

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

        val tracks = try {
            interaction.command.strings.values
                .flatMap { url -> lava.loadItem(url) }
                .takeUnless { it.isEmpty() }
                .otherwise {
                    interaction.respondEphemeral { content = "No matches" }
                    return@on
                }
        } catch (ex: FriendlyException) {
            interaction.respondEphemeral {
                embed {
                    color = when (ex.severity) {
                        FriendlyException.Severity.COMMON -> null
                        FriendlyException.Severity.SUSPICIOUS -> Color(190, 145, 23)
                        FriendlyException.Severity.FAULT -> Color(199, 84, 80)
                        null -> null
                    }

                    title = "Error"
                    description = ex.message
                }
            }

            return@on
        }

        tracks.forEach {
            session.enqueue(it, interaction.user)
        }

        response.delete()
    }

    kord.on<GuildButtonInteractionCreateEvent> {
        val session = sessions[interaction.guildId].otherwise {
            return@on
        }

        session.handleButton(interaction.componentId)

        interaction.respondEphemeral { }
    }

    kord.login {
        intents += Intent.GuildVoiceStates
    }
}
