package com.phoneproxy.app.proxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Snapshot of the proxy's current state, observed by the UI. */
data class ProxyState(
    val running: Boolean = false,
    val port: Int = ProxyConfig.DEFAULT_PORT,
    val authEnabled: Boolean = false,
    val error: String? = null,
)

/** Process-wide bridge between [ProxyService] and the UI. */
object ProxyController {
    private val _state = MutableStateFlow(ProxyState())
    val state: StateFlow<ProxyState> = _state.asStateFlow()

    fun update(state: ProxyState) {
        _state.value = state
    }
}
