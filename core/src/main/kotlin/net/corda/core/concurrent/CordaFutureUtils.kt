@file:JvmName("CordaFutureUtils")
package net.corda.core.concurrent

import net.corda.core.utilities.getOrThrow
import org.slf4j.Logger
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** @return a fresh [OpenFuture]. */
fun <V> openFuture(): OpenFuture<V> = CordaFutureImpl()

/** @return a done future with the given value as its outcome. */
fun <V> doneFuture(value: V): CordaFuture<V> = CordaFutureImpl<V>().apply { set(value) }

/** @return a future that will have the same outcome as the given block, when this executor has finished running it. */
fun <V> Executor.fork(block: () -> V): CordaFuture<V> = CordaFutureImpl<V>().also { execute { it.capture(block) } }

/** When this future is done, do [match]. */
fun <V, W, X> CordaFuture<out V>.thenMatch(success: (V) -> W, failure: (Throwable) -> X) = then { match(success, failure) }

/** When this future is done and the outcome is failure, log the throwable. */
fun CordaFuture<*>.andForget(log: Logger) = thenMatch({}, { log.error("Background task failed:", it) })

/**
 * Returns a future that will have an outcome of applying the given transform to this future's value.
 * But if this future fails, the transform is not invoked and the returned future becomes done with the same throwable.
 */
fun <V, W> CordaFuture<out V>.map(transform: (V) -> W): CordaFuture<W> = CordaFutureImpl<W>().also { result ->
    thenMatch({
        result.capture { transform(it) }
    }, {
        result.setException(it)
    })
}

/**
 * Returns a future that will have the same outcome as the future returned by the given transform.
 * But if this future or the transform fails, the returned future's outcome is the same throwable.
 * In the case where this future fails, the transform is not invoked.
 */
fun <V, W> CordaFuture<out V>.flatMap(transform: (V) -> CordaFuture<out W>): CordaFuture<W> = CordaFutureImpl<W>().also { result ->
    thenMatch(success@ {
        result.captureLater(try {
            transform(it)
        } catch (t: Throwable) {
            result.setException(t)
            return@success
        })
    }, {
        result.setException(it)
    })
}

/**
 * If all of the given futures succeed, the returned future's outcome is a list of all their values.
 * The values are in the same order as the futures in the collection, not the order of completion.
 * If at least one given future fails, the returned future's outcome is the first throwable that was thrown.
 * Any subsequent throwables are added to the first one as suppressed throwables, in the order they are thrown.
 * If no futures were given, the returned future has an immediate outcome of empty list.
 * Otherwise the returned future does not have an outcome until all given futures have an outcome.
 * Unlike Guava's Futures.allAsList, this method never hides failures/hangs subsequent to the first failure.
 */
fun <V> Collection<CordaFuture<out V>>.transpose(): CordaFuture<List<V>> {
    if (isEmpty()) return doneFuture(emptyList())
    val transpose = CordaFutureImpl<List<V>>()
    val stateLock = Any()
    var failure: Throwable? = null
    var remaining = size
    forEach {
        it.then { doneFuture ->
            synchronized(stateLock) {
                doneFuture.match({}, { throwable ->
                    if (failure == null) failure = throwable else failure!!.addSuppressed(throwable)
                })
                if (--remaining == 0) {
                    if (failure == null) transpose.set(map { it.getOrThrow() }) else transpose.setException(failure!!)
                }
            }
        }
    }
    return transpose
}

internal fun <V> Future<V>.get(timeout: Duration? = null): V = if (timeout == null) get() else get(timeout.toNanos(), TimeUnit.NANOSECONDS)
