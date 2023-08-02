import com.herman.markdown_dsl.elements.link
import com.herman.markdown_dsl.markdown
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import dev.kord.common.Locale
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
import dev.kord.rest.builder.message.modify.InteractionResponseModifyBuilder
import dev.kord.rest.builder.message.modify.actionRow
import dev.kord.rest.builder.message.modify.embed
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
                    val locale = channel.guild.asGuild().preferredLocale
                    response.update {
                        render(locale, it)
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
        if (state.channelId == channel.id) {
            listeners.add(state.userId)
        } else {
            listeners.remove(state.userId)
        }

        disconnect()
    }

    suspend fun handleComponent(interaction: GuildComponentInteraction): Boolean {
        val id = ComponentId.entries
            .firstOrNull { it.id == interaction.componentId }
            .otherwise { return false }

        when (id) {
            ComponentId.Resume -> playlist.setPlaying(true)
            ComponentId.Pause -> playlist.setPlaying(false)
            ComponentId.Skip -> playlist.skip()
            ComponentId.Clear -> playlist.clear()
            ComponentId.Leave -> disconnect()
        }

        return true
    }

    suspend fun disconnect() {
        playlist.close()
        connection.shutdown()
        response.delete()

        doneMutable.close()
    }
}

private val AudioTrack.requestedBy: Member?
    get() = getUserData(Member::class.java)

private fun InteractionResponseModifyBuilder.render(locale: Locale, state: Snapshot) {
    renderMainTrack(locale, state.isPlaying, state.currentTrack)
    renderQueue(locale, state.queue)
    renderActions(locale, state.isPlaying, state.currentTrack, state.queue)
}

private fun InteractionResponseModifyBuilder.renderMainTrack(
    locale: Locale,
    isPlaying: Boolean,
    currentTrack: AudioTrack?,
) {
    val info = currentTrack?.info ?: return

    localeScope(locale) {
        embed {
            title = info.title
            description = buildString {
                appendLine(
                    if (isPlaying) {
                        localize("summary_status_playing")
                    } else {
                        localize("summary_status_paused")
                    }
                )
                currentTrack.requestedBy.letNotNull { appendLine(localize("summary_requester", it.mention)) }
            }
            url = info.uri

            author {
                name = info.author
            }

            footer {
                text = localeScope(locale) { localize(Duration.ofMillis(currentTrack.duration)) }
            }
        }
    }
}

private fun InteractionResponseModifyBuilder.renderQueue(locale: Locale, queue: Collection<AudioTrack>) {
    if (queue.isEmpty()) {
        return
    }

    localeScope(locale) {
        val trackList = buildString {
            for ((index, track) in queue.withIndex()) {
                val item = markdown {
                    val id = index + 1
                    val title = track.info.title.tryAugment { link(URI(track.info.uri)) }
                    val requester = track.requestedBy?.mention

                    line(
                        if (requester == null) {
                            localize("queue_line_unknown_requester", id, title)
                        } else {
                            localize("queue_line_known_requester", id, title, requester)
                        }
                    )
                }.toString()

                if (length + item.length > 4000) {
                    appendLine(localize("queue_overflow", queue.size - index))
                    break
                }

                appendLine(item)
            }
        }

        embed {
            title = localize("queue_title")
            description = trackList
        }
    }

}

private fun InteractionResponseModifyBuilder.renderActions(
    locale: Locale,
    isPlaying: Boolean,
    currentTrack: AudioTrack?,
    queue: Collection<AudioTrack>,
) {
    val hasTrack = currentTrack != null
    val hasQueue = queue.isNotEmpty()

    localeScope(locale) {
        actionRow {
            if (hasTrack) {
                if (isPlaying) {
                    interactionButton(ButtonStyle.Secondary, ComponentId.Pause.id) {
                        emoji(ReactionEmoji.Unicode(localize("interaction_pause_emoji")))
                        label = localize("interaction_pause_label")
                    }
                } else {
                    interactionButton(ButtonStyle.Secondary, ComponentId.Resume.id) {
                        emoji(ReactionEmoji.Unicode(localize("interaction_play_emoji")))
                        label = localize("interaction_play_label")
                    }
                }
            }

            if (hasTrack && hasQueue) {
                interactionButton(ButtonStyle.Secondary, ComponentId.Skip.id) {
                    emoji(ReactionEmoji.Unicode(localize("interaction_skip_emoji")))
                    label = localize("interaction_skip_label")
                }
            }

            if (hasQueue) {
                interactionButton(ButtonStyle.Secondary, ComponentId.Clear.id) {
                    emoji(ReactionEmoji.Unicode(localize("interaction_clear_emoji")))
                    label = localize("interaction_clear_label")
                }
            }

            interactionButton(ButtonStyle.Secondary, ComponentId.Leave.id) {
                emoji(ReactionEmoji.Unicode(localize("interaction_leave_emoji")))
                label = localize("interaction_leave_label")
            }
        }
    }
}