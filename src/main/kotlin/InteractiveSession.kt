import com.herman.markdown_dsl.elements.link
import com.herman.markdown_dsl.markdown
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import dev.kord.common.annotation.KordVoice
import dev.kord.common.entity.ButtonStyle
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.BaseVoiceChannelBehavior
import dev.kord.core.behavior.channel.connect
import dev.kord.core.builder.components.emoji
import dev.kord.core.entity.Member
import dev.kord.core.entity.ReactionEmoji
import dev.kord.rest.builder.message.modify.InteractionResponseModifyBuilder
import dev.kord.rest.builder.message.modify.actionRow
import dev.kord.rest.builder.message.modify.embed
import dev.kord.rest.request.KtorRequestException
import dev.kord.voice.AudioFrame
import dev.kord.voice.VoiceConnection
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds

private data class Snapshot(val isPlaying: Boolean, val currentTrack: AudioTrack?, val queue: Collection<AudioTrack>)

private enum class ButtonId(val id: String) {
    Resume("Resume"),
    Pause("Pause"),
    Skip("skip"),
    Clear("clear"),
    Leave("leave"),
}

@OptIn(KordVoice::class, FlowPreview::class)
class InteractiveSession private constructor(
    kord: Kord,
    private val playlist: Playlist,
    private val connection: VoiceConnection,
    private val response: StatefulResponse.Handle,
) {
    val done: ReceiveChannel<Nothing> get() = doneMutable
    private val doneMutable: Channel<Nothing> = Channel { }

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
            return InteractiveSession(voiceChannel.kord, playlist, connection, response)
        }
    }

    init {
        combine(playlist.isPlaying, playlist.current, playlist.queue) { isPlaying, current, queue ->
            Snapshot(isPlaying, current, queue)
        }
            .debounce(300.milliseconds)
            .onEach {
                try {
                    response.update {
                        render(it)
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

    suspend fun handleButton(id: CharSequence): Boolean {
        val button = ButtonId.entries
            .firstOrNull { it.id == id }
            .otherwise { return false }

        when (button) {
            ButtonId.Resume -> playlist.setPlaying(true)
            ButtonId.Pause -> playlist.setPlaying(false)
            ButtonId.Skip -> playlist.skip()
            ButtonId.Clear -> playlist.clear()
            ButtonId.Leave -> disconnect()
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

private fun InteractionResponseModifyBuilder.render(state: Snapshot) {
    renderMainTrack(state.isPlaying, state.currentTrack)
    renderQueue(state.queue)
    renderActions(state.isPlaying, state.currentTrack, state.queue)
}

private fun InteractionResponseModifyBuilder.renderMainTrack(isPlaying: Boolean, currentTrack: AudioTrack?) {
    val info = currentTrack?.info ?: return

    embed {
        title = info.title
        description = buildString {
            appendLine(if (isPlaying) ":arrow_forward: Now playing" else ":pause_button: Paused")
            currentTrack.requestedBy.letNotNull { append("Requested by ${it.mention}") }
        }
        url = info.uri

        author {
            name = info.author
        }

        footer {
            text = java.time.Duration.ofMillis(currentTrack.duration).toHumanString()
        }
    }
}

private fun InteractionResponseModifyBuilder.renderQueue(queue: Collection<AudioTrack>) {
    if (queue.isEmpty()) {
        return
    }

    val trackList = buildString {
        for ((index, track) in queue.withIndex()) {
            val item = markdown {
                val id = index + 1
                val title = track.info.title.tryAugment { link(URI(track.info.uri)) }
                val suffix = track.requestedBy
                    .letNotNull { ", requested by ${it.mention}" }
                    ?: ""

                line("#$id. $title$suffix")
            }.toString()

            if (length + item.length > 4000) {
                appendLine("And ${queue.size - index} more...")
                break
            }

            appendLine(item)
        }
    }

    embed {
        title = "Next up"
        description = trackList
    }
}

private fun InteractionResponseModifyBuilder.renderActions(
    isPlaying: Boolean,
    currentTrack: AudioTrack?,
    queue: Collection<AudioTrack>
) {
    val hasTrack = currentTrack != null
    val hasQueue = queue.isNotEmpty()
    actionRow {
        if (hasTrack) {
            if (isPlaying) {
                interactionButton(ButtonStyle.Secondary, ButtonId.Pause.id) {
                    emoji(ReactionEmoji.Unicode("""⏸️"""))
                    label = "Pause"
                }
            } else {
                interactionButton(ButtonStyle.Secondary, ButtonId.Resume.id) {
                    emoji(ReactionEmoji.Unicode("""▶️"""))
                    label = "Play"
                }
            }
        }

        if (hasTrack && hasQueue) {
            interactionButton(ButtonStyle.Secondary, ButtonId.Skip.id) {
                label = "Skip"
            }
        }

        if (hasQueue) {
            interactionButton(ButtonStyle.Secondary, ButtonId.Clear.id) {
                label = "Clear Queue"
            }
        }

        interactionButton(ButtonStyle.Secondary, ButtonId.Leave.id) {
            label = "Leave Channel"
        }
    }
}