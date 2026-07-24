package com.aliothmoon.maameow.web

import android.util.Base64
import com.aliothmoon.maameow.data.preferences.ConfigBackupManager
import com.aliothmoon.maameow.data.preferences.TaskChainState
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Optional, password-protected LAN configuration endpoint.
 *
 * It intentionally uses ConfigBackupManager rather than writing DataStore files
 * itself, so the web editor has the same import/export validation path as the UI.
 */
class RemoteWebServerManager(
    private val settings: RemoteWebSettings,
    private val backupManager: ConfigBackupManager,
    private val taskChainState: TaskChainState,
) {
    @Volatile private var server: Server? = null
    @Volatile private var runningConfig: RemoteWebConfig? = null

    @Synchronized
    fun applySettings(): Result<Unit> = runCatching {
        val desired = settings.config.value
        if (!desired.enabled) {
            server?.stop()
            server = null
            runningConfig = null
            return@runCatching
        }
        require(desired.password.length >= 8) { "访问密码至少需要 8 个字符" }
        if (desired == runningConfig && server != null) return@runCatching
        server?.stop()
        server = null
        runningConfig = null
        server = Server(desired, backupManager, taskChainState).also {
            it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        }
        runningConfig = desired
    }

    fun startIfEnabled() { applySettings() }

    private class Server(
        private val config: RemoteWebConfig,
        private val backupManager: ConfigBackupManager,
        private val taskChainState: TaskChainState,
    ) : NanoHTTPD(config.port) {

        override fun serve(session: IHTTPSession): Response = try {
            when {
                session.uri == "/" -> htmlResponse(INDEX_HTML)
                session.uri.startsWith("/api/") && !authorized(session) ->
                    newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{\"error\":\"密码错误\"}")
                session.uri == "/api/config" && session.method == Method.GET -> exportConfig()
                session.uri == "/api/config" && session.method in setOf(Method.PUT, Method.POST) -> importConfig(session)
                session.uri == "/api/status" && session.method == Method.GET -> status()
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"not found\"}")
            }
        } catch (error: Exception) {
            newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":${jsonString(error.message ?: "请求失败")} }")
        }

        private fun authorized(session: IHTTPSession): Boolean {
            val header = session.headers["authorization"].orEmpty()
            if (!header.startsWith("Basic ", ignoreCase = true)) return false
            val decoded = runCatching {
                String(Base64.decode(header.substringAfter(' ').trim(), Base64.DEFAULT), StandardCharsets.UTF_8)
            }.getOrDefault("")
            return decoded == ":${config.password}"
        }

        private fun exportConfig(): Response {
            val bytes = ByteArrayOutputStream().also { output ->
                runBlocking { backupManager.exportTo(output, sanitize = false) }
            }.toByteArray()
            return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", bytes.inputStream(), bytes.size.toLong())
        }

        private fun importConfig(session: IHTTPSession): Response {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val body = files["postData"].orEmpty()
            require(body.isNotBlank()) { "配置内容不能为空" }
            runBlocking { backupManager.importFrom(ByteArrayInputStream(body.toByteArray(StandardCharsets.UTF_8))) }
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true}")
        }

        private fun status(): Response {
            val profiles = taskChainState.profiles.value
            val active = taskChainState.activeProfileId.value
            val names = profiles.joinToString(",") { "{\"id\":${jsonString(it.id)},\"name\":${jsonString(it.name)}}" }
            val body = "{\"activeProfileId\":${jsonString(active)},\"profiles\":[$names]}"
            return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body)
        }

        private fun htmlResponse(body: String) = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)
        private fun jsonString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }
}

private const val INDEX_HTML = """<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1"><title>MaaMeow Web</title><style>body{max-width:900px;margin:24px auto;padding:0 16px;font:15px system-ui;background:#101622;color:#e9eef9}input,textarea,button{box-sizing:border-box;padding:10px;border-radius:8px;border:1px solid #394860;background:#192334;color:inherit}textarea{width:100%;height:60vh;font:13px ui-monospace,monospace}button{cursor:pointer;background:#3978d5;border:0}#bar{display:flex;gap:8px;margin:12px 0}#password{width:260px}small{color:#a8b3c7}</style><h1>MaaMeow Web</h1><small>局域网管理页。密码不会保存到浏览器本地存储。</small><div id=bar><input id=password type=password placeholder="访问密码"><button onclick=loadConfig()>读取配置</button><button onclick=saveConfig()>保存全部配置</button><button onclick=status()>状态</button></div><pre id=status></pre><textarea id=config spellcheck=false placeholder="点击“读取配置”"></textarea><script>const auth=()=>({Authorization:'Basic '+btoa(':'+document.querySelector('#password').value)});async function loadConfig(){let r=await fetch('/api/config',{headers:auth()});if(!r.ok)return alert(await r.text());config.value=await r.text()}async function saveConfig(){if(!confirm('将覆盖当前全部设置、Profile 与定时策略，确定继续？'))return;let r=await fetch('/api/config',{method:'PUT',headers:{...auth(),'Content-Type':'application/json'},body:config.value});if(!r.ok)return alert(await r.text());alert('已保存')}async function status(){let r=await fetch('/api/status',{headers:auth()});document.querySelector('#status').textContent=r.ok?JSON.stringify(await r.json(),null,2):await r.text()}</script>"""
