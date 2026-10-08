package com.sublearn.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Injectable dispatchers so tests can run everything on one thread. */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val default: CoroutineDispatcher
    val io: CoroutineDispatcher
    val computation: CoroutineDispatcher

    companion object Default : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Main.immediate
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val computation: CoroutineDispatcher = Dispatchers.Default
    }
}

/** A dispatcher provider that keeps every coroutine on the caller's thread; used by unit tests. */
class TrivialDispatchers(private val dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val main: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val computation: CoroutineDispatcher = dispatcher
}
