import dev.kord.common.Locale
import dev.kord.common.asJavaLocale
import dev.kord.core.entity.Guild
import org.jetbrains.annotations.PropertyKey
import java.text.MessageFormat
import java.time.Duration
import java.util.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

data class LocaleContext(val locale: Locale?): CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> = Key
    companion object Key : CoroutineContext.Key<LocaleContext>

    constructor(guild: Guild?) : this(guild?.preferredLocale)
}

data class MultiLocale<T>(val default: T, private val localized: Map<Locale, T>) :
    Map<Locale, T> by localized

class LocaleScope internal constructor(locale: Locale?) {
    private val bundle: ResourceBundle

    init {
        val javaLocale = locale?.asJavaLocale() ?: java.util.Locale.getDefault()
        bundle = ResourceBundle.getBundle("userFacingStrings", javaLocale, javaClass.module)
    }

    fun localize(@PropertyKey(resourceBundle = "userFacingStrings") key: String, vararg args: Any): String {
        return MessageFormat(bundle.getString(key), bundle.locale).format(args)
    }
}

suspend fun <T> localeScope(block: suspend LocaleScope.() -> T): T =
    block(LocaleScope(coroutineContext[LocaleContext.Key]?.locale))

fun <T> multiLocale(locales: List<Locale> = Locale.ALL, block: LocaleScope.() -> T): MultiLocale<T> = MultiLocale(
    block(LocaleScope(null)),
    locales.associateWith { block(LocaleScope(it)) },
)

fun LocaleScope.localize(duration: Duration): String = buildString {
    suspend fun SequenceScope<CharSequence>.yieldNonZero(key: String, value: Int) {
        if (value > 0) {
            yield(localize(key, value))
        }
    }

    sequence {
        yieldNonZero("duration_hours", duration.toHoursPart())
        yieldNonZero("duration_minutes", duration.toMinutesPart())
        yieldNonZero("duration_seconds", duration.toSecondsPart())
    }.joinToString(localize("duration_separator"))
}