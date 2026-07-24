package com.aliothmoon.maameow.web

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RemoteWebConfig(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val password: String = "",
) {
    companion object {
        const val DEFAULT_PORT = 8787
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
    }
}

/** Private, device-local settings for the optional LAN administration server. */
class RemoteWebSettings(context: Context) {
    private val preferences = context.getSharedPreferences("remote_web", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(load())
    val config: StateFlow<RemoteWebConfig> = _config.asStateFlow()

    fun save(enabled: Boolean, portText: String, password: String): Result<RemoteWebConfig> = runCatching {
        require(password.length >= 8) { "访问密码至少需要 8 个字符" }
        val port = portText.toIntOrNull() ?: throw IllegalArgumentException("端口必须是数字")
        require(port in RemoteWebConfig.MIN_PORT..RemoteWebConfig.MAX_PORT) {
            "端口范围为 ${RemoteWebConfig.MIN_PORT}-${RemoteWebConfig.MAX_PORT}"
        }
        val next = RemoteWebConfig(enabled, port, password)
        preferences.edit()
            .putBoolean("enabled", next.enabled)
            .putInt("port", next.port)
            .putString("password", next.password)
            .apply()
        _config.value = next
        next
    }

    private fun load() = RemoteWebConfig(
        enabled = preferences.getBoolean("enabled", false),
        port = preferences.getInt("port", RemoteWebConfig.DEFAULT_PORT)
            .coerceIn(RemoteWebConfig.MIN_PORT, RemoteWebConfig.MAX_PORT),
        password = preferences.getString("password", "").orEmpty(),
    )
}
