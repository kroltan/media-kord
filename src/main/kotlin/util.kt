import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

inline fun <T> T?.otherwise(block: () -> Nothing): T {
    if (this == null) {
        block()
    }

    return this
}

inline fun <K, V> MutableMap<K, V>.replace(key: K, block: (V?) -> V): V {
    val value = block(this[key])
    this[key] = value
    return value
}

inline fun <T, U> T?.letNotNull(block: (T) -> U): U? {
    return if (this == null) {
        null
    } else {
        block(this)
    }
}

inline fun <T> T.tryAugment(block: T.() -> T): T {
    return try {
        block()
    } catch (e: Exception) {
        this
    }
}

suspend fun AudioPlayerManager.loadItem(identifier: String): Collection<AudioTrack> {
    return suspendCoroutine {
        this.loadItem(identifier, object : AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack?) {
                it.resume(listOf(track.otherwise { return@trackLoaded }))
            }

            override fun playlistLoaded(playlist: AudioPlaylist?) {
                it.resume(playlist.otherwise { return@playlistLoaded }.tracks)
            }

            override fun noMatches() {
                it.resume(emptyList())
            }

            override fun loadFailed(exception: FriendlyException?) {
                it.resumeWithException(exception!!)
            }
        })
    }
}