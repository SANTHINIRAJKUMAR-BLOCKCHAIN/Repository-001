package net.corda.core.flows.matchers.flow

import com.natpryce.hamkrest.Matcher
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.has
import net.corda.core.flows.matchers.willThrow
import net.corda.core.flows.matchers.willReturn
import net.corda.core.internal.FlowStateMachine

/**
 * Matches a Flow that succeeds with a result matched by the given matcher
 */
fun <T> willReturn() = has(FlowStateMachine<T>::resultFuture, willReturn())

fun <T> willReturn(expected: T): Matcher<FlowStateMachine<out T?>> = net.corda.core.flows.matchers.flow.willReturn(equalTo(expected))

/**
 * Matches a Flow that succeeds with a result matched by the given matcher
 */
fun <T> willReturn(successMatcher: Matcher<T>) = has(
        FlowStateMachine<out T>::resultFuture,
        willReturn(successMatcher))

/**
 * Matches a Flow that fails, with an exception matched by the given matcher.
 */
inline fun <reified E: Exception> willThrow(failureMatcher: Matcher<E>) = has(
        FlowStateMachine<*>::resultFuture,
        willThrow(failureMatcher))

/**
 * Matches a Flow that fails, with an exception of the specified type.
 */
inline fun <reified E: Exception> willThrow() = has(
        FlowStateMachine<*>::resultFuture,
        willThrow<E>())