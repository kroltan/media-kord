import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import dev.kord.common.annotation.KordVoice
import dev.kord.common.entity.ButtonStyle
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.BaseVoiceChannelBehavior
import dev.kord.core.behavior.channel.connect
import dev.kord.core.builder.components.emoji
import dev.kord.core.entity.Member
import dev.kord.core.entity.ReactionEmoji
import dev.kord.core.entity.VoiceState
import dev.kord.core.entity.interaction.GuildComponentInteraction
import dev.kord.rest.builder.message.MessageBuilder
import dev.kord.rest.builder.message.actionRow
import dev.kord.rest.builder.message.embed
import dev.kord.rest.request.KtorRequestException
import dev.kord.voice.AudioFrame
import dev.kord.voice.VoiceConnection
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.jetbrains.annotations.NonNls
import java.net.URI
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private data class Snapshot(val isPlaying: Boolean, val currentTrack: AudioTrack?, val queue: Collection<AudioTrack>)

private enum class ComponentId(@NonNls val id: String) {
    Resume("resume"),
    Pause("pause"),
    Skip("skip"),
    Clear("clear"),
    Leave("leave"),
}

@OptIn(KordVoice::class, FlowPreview::class)
class InteractiveSession private constructor(
    kord: Kord,
    private val channel: BaseVoiceChannelBehavior,
    private val playlist: Playlist,
    private val connection: VoiceConnection,
    private val response: StatefulResponse.Handle,
) {
    val done: ReceiveChannel<Nothing> get() = doneMutable
    private val doneMutable: Channel<Nothing> = Channel { }
    private val listeners: MutableSet<Snowflake> = mutableSetOf()

    companion object {
        suspend fun fromOriginalInteraction(
            player: AudioPlayer,
            voiceChannel: BaseVoiceChannelBehavior,
            response: StatefulResponse.Handle,
        ): InteractiveSession {
            val connection = voiceChannel.connect {
                selfDeaf = true

                audioProvider {
                    player.provide().letNotNull { AudioFrame.fromData(it.data) }
                }
            }

            val playlist = Playlist(player)
            return InteractiveSession(voiceChannel.kord, voiceChannel, playlist, connection, response)
        }
    }

    init {
        kord.launch {
            channel.voiceStates
                .map { it.userId }
                .filter { it != connection.data.selfId }
                .toCollection(listeners)
        }

        combine(playlist.isPlaying, playlist.current, playlist.queue) { isPlaying, current, queue ->
            Snapshot(isPlaying, current, queue)
        }
            .debounce(300.milliseconds)
            .onEach {
                try {
                    localeScope {
                        response.update {
                            render(this@localeScope, it)
                        }
                    }
                } catch (exception: KtorRequestException) {
                    // Editing after disconnect
                    if (exception.status.code != 404) {
                        throw exception
                    }
                }
            }
            .launchIn(kord)
    }

    fun enqueue(track: AudioTrack, requestedBy: Member) {
        track.userData = requestedBy
        playlist.enqueue(track)
    }

    suspend fun handleVoiceState(state: VoiceState) {
        if (state.getMemberOrNull()?.isBot == true) {
            return
        }

        if (state.channelId == channel.id) {
            listeners.add(state.userId)
        } else {
            listeners.remove(state.userId)
        }

        if (listeners.isEmpty()) {
            disconnect()
        }
    }

    suspend fun handleComponent(interaction: GuildComponentInteraction) {
        val id = ComponentId.entries
            .firstOrNull { it.id == interaction.componentId }
            ?: throw Error("Missing component identifier")

        when (id) {
            ComponentId.Resume -> playlist.setPlaying(true)
            ComponentId.Pause -> playlist.setPlaying(false)
            ComponentId.Skip -> playlist.skip()
            ComponentId.Clear -> playlist.clear()
            ComponentId.Leave -> disconnect()
        }
    }

    suspend fun disconnect() {
        response.delete()
        playlist.close()
        connection.shutdown()

        doneMutable.close()
    }
}

private val AudioTrack.requestedBy: Member?
    get() = getUserData(Member::class.java)

private fun MessageBuilder.render(locale: LocaleScope, state: Snapshot) {
    renderMainTrack(locale, state.isPlaying, state.currentTrack)
    renderQueue(locale, state.queue)
    renderActions(locale, state.isPlaying, state.currentTrack, state.queue)
}

private fun MessageBuilder.renderMainTrack(
    locale: LocaleScope,
    isPlaying: Boolean,
    currentTrack: AudioTrack?,
) {
    embed {
        val track = currentTrack.otherwise {
            description = locale.localize("summary_idle", locale.localize("command_play_name"))

            author {
                locale.localize("summary_idle_title")
            }
            return@embed
        }

        title = track.info.title
        description = buildString {
            appendLine(
                if (isPlaying) {
                    locale.localize("summary_status_playing")
                } else {
                    locale.localize("summary_status_paused")
                }
            )
            track.requestedBy.letNotNull { appendLine(locale.localize("summary_requester", it.mention)) }
        }
        url = track.info.uri

        author {
            name = track.info.author
        }

        footer {
            text = locale.localize(Duration.ofMillis(track.duration))
        }
    }

}

private fun MessageBuilder.renderQueue(locale: LocaleScope, queue: Collection<AudioTrack>) {
    if (queue.isEmpty()) {
        return
    }

    val trackList = buildString {
        for ((index, track) in queue.withIndex()) {
            val id = index + 1
            val requester = locale.localize(
                "queue_line_tooltip_requester",
                track.requestedBy?.effectiveName ?: locale.localize("queue_line_tooltip_unknown")
            )
            val title = track.info.title.tryAugment {
                link(
                    URI(track.info.uri),
                    title = requester
                )
            }

            val item = locale.localize("queue_line", id, title, requester)

            if (length + item.length > 4000) {
                appendLine(locale.localize("queue_overflow", queue.size - index))
                break
            }

            appendLine(item)
        }
    }

    embed {
        title = locale.localize("queue_title")
        description = trackList
    }

}

private fun MessageBuilder.renderActions(
    locale: LocaleScope,
    isPlaying: Boolean,
    currentTrack: AudioTrack?,
    queue: Collection<AudioTrack>,
) {
    val hasTrack = currentTrack != null
    val hasQueue = queue.isNotEmpty()

    actionRow {
        if (hasTrack) {
            if (isPlaying) {
                interactionButton(ButtonStyle.Secondary, ComponentId.Pause.id) {
                    emoji(ReactionEmoji.Unicode(locale.localize("interaction_pause_emoji")))
                    label = locale.localize("interaction_pause_label")
                }
            } else {
                interactionButton(ButtonStyle.Secondary, ComponentId.Resume.id) {
                    emoji(ReactionEmoji.Unicode(locale.localize("interaction_play_emoji")))
                    label = locale.localize("interaction_play_label")
                }
            }
        }

        if (hasTrack && hasQueue) {
            interactionButton(ButtonStyle.Secondary, ComponentId.Skip.id) {
                emoji(ReactionEmoji.Unicode(locale.localize("interaction_skip_emoji")))
                label = locale.localize("interaction_skip_label")
            }
        }

        if (hasQueue) {
            interactionButton(ButtonStyle.Secondary, ComponentId.Clear.id) {
                emoji(ReactionEmoji.Unicode(locale.localize("interaction_clear_emoji")))
                label = locale.localize("interaction_clear_label")
            }
        }

        interactionButton(ButtonStyle.Secondary, ComponentId.Leave.id) {
            emoji(ReactionEmoji.Unicode(locale.localize("interaction_leave_emoji")))
            label = locale.localize("interaction_leave_label")
        }
    }
}