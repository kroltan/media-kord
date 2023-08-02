import dev.kord.common.Locale
import dev.kord.common.asJavaLocale
import org.jetbrains.annotations.PropertyKey
import java.text.MessageFormat
import java.time.Duration
import java.util.*

data class MultiLocale<T>(val default: T, private val localized: Map<Locale, T>) :
    Map<Locale, T> by localized

data class LocaleScope(private val bundle: ResourceBundle) {
    fun localize(@PropertyKey(resourceBundle = "userFacingStrings") key: String, vararg args: Any): String {
        return MessageFormat(bundle.getString(key), bundle.locale).format(args)
    }
}

fun <T> localeScope(locale: Locale? = null, block: LocaleScope.() -> T): T {
    val javaLocale = locale?.asJavaLocale() ?: java.util.Locale.getDefault()
    val bundle = ResourceBundle.getBundle("userFacingStrings", javaLocale, block.javaClass.module)
    val context = LocaleScope(bundle)

    return block(context)
}

fun <T> multiLocale(locales: List<Locale> = Locale.ALL, block: LocaleScope.() -> T): MultiLocale<T> {
    val default = localeScope(null, block)
    val specialized = mutableMapOf<Locale, T>()

    for (locale in locales) {
        specialized[locale] = localeScope(locale, block)
    }

    return MultiLocale(default, specialized)
}

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