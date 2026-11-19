package com.phoenix.app

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Короткие сообщения главному экрану (показываются одним Snackbar вместо разрозненных Toast); можно слать из любого потока. */
object UiMessages {
    val flow = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    fun post(text: String) { flow.tryEmit(text) }
}
