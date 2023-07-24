import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.event.*
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

class Playlist(private val player: AudioPlayer) : AutoCloseable {
    val isPlaying: StateFlow<Boolean> get() = isPlayingMutable
    val current: StateFlow<AudioTrack?> get() = currentMutable
    val queue: StateFlow<Collection<AudioTrack>> get() = queueMutable
    private val isPlayingMutable: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val currentMutable: MutableStateFlow<AudioTrack?> = MutableStateFlow(null)
    private val queueMutable: MutableStateFlow<List<AudioTrack>> = MutableStateFlow(emptyList())

    init {
        player.addListener listener@{
            when (it) {
                is PlayerPauseEvent -> {
                    isPlayingMutable.value = false
                }

                is PlayerResumeEvent -> {
                    isPlayingMutable.value = true
                }

                is TrackStartEvent -> {
                    isPlayingMutable.value = true
                    currentMutable.value = it.track
                }

                is TrackEndEvent -> {
                    if (it.endReason == AudioTrackEndReason.REPLACED) {
                        return@listener
                    }

                    val head = popQueue().otherwise {
                        isPlayingMutable.value = false
                        currentMutable.value = null
                        return@listener
                    }

                    player.playTrack(head)
                }

                is TrackExceptionEvent -> {
                    isPlayingMutable.value = false
                }
            }
        }
    }

    fun enqueue(track: AudioTrack) {
        if (player.playingTrack == null) {
            player.playTrack(track)
        } else {
            queueMutable.update {
                it + track
            }
        }
    }

    fun setPlaying(playing: Boolean) {
        player.isPaused = !playing
    }

    fun skip() {
        player.stopTrack()
    }

    fun clear() {
        queueMutable.value = emptyList()
    }

    private fun popQueue(): AudioTrack? {
        val list = queueMutable.getAndUpdate {
            it.takeLast(it.count() - 1)
        }

        return list.getOrNull(0)
    }

    override fun close() {
        currentMutable.value = null
        queueMutable.value = emptyList()
        player.destroy()
    }
}