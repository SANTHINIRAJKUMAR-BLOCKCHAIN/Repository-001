package net.corda.cliutils

import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.impl.LogEventFactory
import org.apache.logging.log4j.message.Message
import org.apache.logging.log4j.message.SimpleMessage
import java.util.*

fun registerErrorCodesLoggerForThrowables() {

    val loggerContext = LoggerContext.getContext(false)
    for (logger in loggerContext.configuration.loggers.values) {
        val existingFactory = logger.logEventFactory
        logger.logEventFactory = LogEventFactory { loggerName, marker, fqcn, level, message, properties, error -> existingFactory.createEvent(loggerName, marker, fqcn, level, message?.withErrorCodeFor(error), properties, error) }
    }
}

private fun Message.withErrorCodeFor(error: Throwable?): Message {

    // TODO sollecitom investigate whether you could use the MDC instead here (would be quite cleaner)
    return error?.let { CompositeMessage("$formattedMessage [errorCode=${it.errorCode()}]", format, parameters, throwable) } ?: this
}

private fun Throwable.errorCode(hashedFields: (Throwable) -> Array<out Any?> = Throwable::defaultHashedFields): String {

    val hash = staticLocationBasedHash(hashedFields)
    return hash.toBase(36)
}

private fun Throwable.staticLocationBasedHash(hashedFields: (Throwable) -> Array<out Any?>, visited: Set<Throwable> = setOf(this)): Int {

    val cause = this.cause
    val fields = hashedFields.invoke(this)
    return when {
        cause != null && !visited.contains(cause) -> Objects.hash(*fields, cause.staticLocationBasedHash(hashedFields, visited + cause))
        else -> Objects.hash(*fields)
    }
}

private fun Int.toBase(base: Int): String = Integer.toUnsignedString(this, base)

private fun Array<StackTraceElement?>.customHashCode(maxElementsToConsider: Int = this.size): Int {

    return Arrays.hashCode(take(maxElementsToConsider).map { it?.customHashCode() ?: 0 }.toIntArray())
}

private fun StackTraceElement.customHashCode(hashedFields: (StackTraceElement) -> Array<out Any?> = StackTraceElement::defaultHashedFields): Int {

    return Objects.hash(*hashedFields.invoke(this))
}

private fun Throwable.defaultHashedFields(): Array<out Any?> {

    return arrayOf(this::class.java.name, stackTrace?.customHashCode(3) ?: 0)
}

private fun StackTraceElement.defaultHashedFields(): Array<out Any?> {

    return arrayOf(className, methodName)
}

private class CompositeMessage(message: String?, private val formatArg: String?, private val parameters: Array<out Any?>?, private val error: Throwable?) : SimpleMessage(message) {

    override fun getThrowable(): Throwable? = error

    override fun getParameters(): Array<out Any?>? = parameters

    override fun getFormat(): String? = formatArg
}