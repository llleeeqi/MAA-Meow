package com.aliothmoon.maameow.web

import android.util.Base64
import com.aliothmoon.maameow.data.preferences.ConfigBackupManager
import com.aliothmoon.maameow.data.preferences.TaskChainState
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
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
            // NanoHTTPD stores raw POST JSON directly in postData, but stores a
            // PUT request body in a temporary file under the content key.
            val body = files["postData"] ?: files["content"]?.let(::File)?.readText().orEmpty()
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

private const val INDEX_HTML = """
<!doctype html>
<html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>MaaMeow Web 设置</title>
<style>
:root{color-scheme:dark}*{box-sizing:border-box}body{max-width:1180px;margin:0 auto;padding:22px;font:15px system-ui,sans-serif;background:#101622;color:#e9eef9}h1{margin:0 0 6px}small,.hint{color:#a8b3c7}.toolbar{position:sticky;top:0;z-index:3;padding:12px 0;background:#101622;display:flex;gap:8px;flex-wrap:wrap}.toolbar input{min-width:190px;flex:1}button,input,textarea,select{font:inherit;color:inherit;border:1px solid #394860;background:#192334;border-radius:8px;padding:9px}button{cursor:pointer;background:#3978d5;border:0;font-weight:650}button.secondary{background:#27354b}button.danger{background:#9d3e4b}.status{min-height:22px;margin:5px 0 14px;color:#b9d3ff}.root{display:grid;gap:12px}.group{border:1px solid #304159;border-radius:12px;background:#151f2e}.group>summary{cursor:pointer;padding:13px 16px;font-weight:700;list-style:none}.group>summary:before{content:'›';display:inline-block;margin-right:9px;font-size:20px;transform:rotate(0deg)}.group[open]>summary:before{transform:rotate(90deg)}.group-body{padding:0 14px 14px;display:grid;gap:10px}.field{display:grid;grid-template-columns:minmax(180px,32%) 1fr;gap:12px;align-items:center;padding:10px;border-radius:8px;background:#192334}.field>label{overflow-wrap:anywhere}.field input,.field textarea,.field select{width:100%;min-width:0}.field textarea{min-height:76px;resize:vertical}.switch{appearance:none;width:48px!important;height:27px;padding:2px;border-radius:99px;background:#42536b;transition:.15s}.switch:checked{background:#3978d5}.switch:before{content:'';display:block;width:21px;height:21px;border-radius:50%;background:white;transition:.15s}.switch:checked:before{transform:translateX(21px)}.array-item{position:relative;margin:8px 0;padding:8px;border:1px solid #34465f;border-radius:8px}.array-actions{display:flex;justify-content:flex-end;margin:7px 0}.empty{padding:10px;color:#a8b3c7}.hidden{display:none!important}@media(max-width:650px){body{padding:14px}.field{grid-template-columns:1fr;gap:6px}.toolbar{position:static}.toolbar input{width:100%}}
</style>
<body>
<h1>MaaMeow Web 设置</h1><small>读取当前 App 配置后自动生成表单；新增配置项会自动显示。保存会写回全部设置、Profile 与定时策略。</small>
<div class="toolbar"><input id="password" type="password" placeholder="访问密码"><input id="search" placeholder="搜索设置项"><button id="load">读取设置</button><button id="save">保存更改</button><button id="reset" class="secondary">放弃未保存更改</button><button id="statusButton" class="secondary">运行状态</button></div>
<div id="status" class="status">请输入密码后读取设置。</div><main id="editor" class="root"></main>
<script>
let config=null,dirty=false;const editor=document.querySelector('#editor'),statusBox=document.querySelector('#status'),searchBox=document.querySelector('#search');
const names={appSettings:'应用设置',notificationSettings:'通知设置',taskProfiles:'任务 Profile',scheduleStrategies:'定时策略',activeProfileId:'当前 Profile',overlayMode:'悬浮窗模式',runMode:'运行模式',updateSource:'更新源',startupBackend:'启动后端',updateChannel:'更新渠道',themeMode:'主题模式',language:'语言',enabled:'启用',name:'名称',id:'标识'};
const choices={overlayMode:['ACCESSIBILITY','SHIZUKU','ROOT'],runMode:['BACKGROUND','FOREGROUND'],updateSource:['GITHUB','MIRROR_CHYAN'],startupBackend:['SHIZUKU','ROOT','ACCESSIBILITY'],updateChannel:['STABLE','BETA'],themeMode:['SYSTEM','LIGHT','DARK'],language:['SYSTEM','ZH','EN'],eventNotificationLevel:['OFF','DEFAULT','HIGH'],backgroundResolution:['P720','P1080'],scheduleType:['FIXED_TIME','INTERVAL']};
const label=k=>names[k]||k.replace(/([a-z])([A-Z])/g,(all,left,right)=>left+' '+right);
const auth=()=>({Authorization:'Basic '+btoa(':'+document.querySelector('#password').value)});
const setStatus=(text,error=false)=>{statusBox.textContent=text;statusBox.style.color=error?'#ffb4ab':'#b9d3ff'};
const markDirty=()=>{if(!dirty){dirty=true;setStatus('有未保存的更改。保存后会同步到 App；部分运行态设置可能需要重启 App 才完全生效。')}};
const secret=k=>/password|token|secret|cdk|webhook|api.?key/i.test(k);
const bool=v=>typeof v==='boolean'||v==='true'||v==='false';
const numeric=v=>typeof v==='number'||(typeof v==='string'&&/^-?\d+(\.\d+)?$/.test(v));
function inputFor(value,key,change){const originalType=typeof value,update=v=>{change(v);markDirty()};let input;
 if(bool(value)){input=document.createElement('input');input.type='checkbox';input.className='switch';input.checked=value===true||value==='true';input.onchange=()=>update(originalType==='string'?String(input.checked):input.checked);return input}
 if(choices[key]){input=document.createElement('select');choices[key].forEach(x=>{const o=document.createElement('option');o.value=x;o.textContent=x;input.append(o)});if(!choices[key].includes(String(value))){const o=document.createElement('option');o.value=value;o.textContent=value;input.append(o)}input.value=value;input.onchange=()=>update(input.value);return input}
 input=document.createElement(numeric(value)?'input':'textarea');if(input.tagName==='INPUT')input.type='number';else input.rows=String(value).length>90?4:1;input.type=secret(key)?'password':input.type;input.value=value??'';input.oninput=()=>{let v=input.value;if(originalType==='number')v=Number(v);update(v)};return input}
function field(key,value,change){const row=document.createElement('div');row.className='field';const l=document.createElement('label');l.textContent=label(key);row.append(l,inputFor(value,key,change));return row}
function group(title,open=true){const d=document.createElement('details');d.className='group';d.open=open;const s=document.createElement('summary');s.textContent=title;d.append(s);const body=document.createElement('div');body.className='group-body';d.append(body);return [d,body]}
function render(value,key,change,depth=0){if(value===null||typeof value!=='object')return field(key,value,change);if(Array.isArray(value)){const [box,body]=group(label(key)+'（'+value.length+'）',depth<2);const draw=()=>{body.innerHTML='';if(!value.length){const empty=document.createElement('div');empty.className='empty';empty.textContent='暂无项目';body.append(empty)}value.forEach((item,index)=>{const wrap=document.createElement('div');wrap.className='array-item';const actions=document.createElement('div');actions.className='array-actions';const remove=document.createElement('button');remove.className='danger';remove.textContent='删除';remove.onclick=()=>{value.splice(index,1);markDirty();draw()};actions.append(remove);wrap.append(actions);wrap.append(render(item,'项目 '+(index+1),next=>{value[index]=next},depth+1));body.append(wrap)});const add=document.createElement('button');add.className='secondary';add.textContent='添加项目';add.onclick=()=>{const item=value.length?JSON.parse(JSON.stringify(value[value.length-1])):'';if(item&&typeof item==='object'&&Object.prototype.hasOwnProperty.call(item,'id'))item.id=globalThis.crypto?.randomUUID?.()||('web-'+Date.now()+'-'+Math.random().toString(36).slice(2));value.push(item);markDirty();draw()};body.append(add)};draw();return box}
 const [box,body]=group(label(key),depth<1);Object.keys(value).forEach(k=>body.append(render(value[k],k,next=>{value[k]=next},depth+1)));return box}
function draw(){editor.innerHTML='';if(!config)return;Object.keys(config).filter(k=>k!=='exportedAt'&&k!=='version').forEach(k=>editor.append(render(config[k],k,next=>{config[k]=next})));applySearch()}
function applySearch(){const q=searchBox.value.trim().toLowerCase();editor.querySelectorAll('.field').forEach(x=>x.classList.toggle('hidden',q&&!x.textContent.toLowerCase().includes(q)));editor.querySelectorAll('.group').forEach(g=>{const visible=[...g.querySelectorAll('.field')].some(x=>!x.classList.contains('hidden'));g.classList.toggle('hidden',q&&!visible);if(q&&visible)g.open=true})}
async function load(){if(dirty&&!confirm('有未保存的更改，确定放弃并重新读取吗？'))return;setStatus('正在读取…');const r=await fetch('/api/config',{headers:auth()});if(!r.ok){setStatus(await r.text(),true);return}config=await r.json();dirty=false;draw();setStatus('已加载。修改表单后点击“保存更改”。')}
async function save(){if(!config)return setStatus('请先读取设置。',true);if(!dirty)return setStatus('没有待保存的更改。');if(!confirm('确认写回当前网页中的全部设置？'))return;setStatus('正在保存…');const r=await fetch('/api/config',{method:'PUT',headers:{...auth(),'Content-Type':'application/json'},body:JSON.stringify(config)});const text=await r.text();if(!r.ok){setStatus(text,true);return}dirty=false;setStatus('保存成功。部分运行态设置可能需要重启 App 后完全生效。')}
async function showStatus(){const r=await fetch('/api/status',{headers:auth()});setStatus(r.ok?JSON.stringify(await r.json()):await r.text(),!r.ok)}
document.querySelector('#load').onclick=load;document.querySelector('#save').onclick=save;document.querySelector('#reset').onclick=()=>{if(!dirty)return setStatus('没有待放弃的更改。');load()};document.querySelector('#statusButton').onclick=showStatus;searchBox.oninput=applySearch;window.addEventListener('beforeunload',e=>{if(dirty){e.preventDefault();e.returnValue=''}});document.addEventListener('keydown',e=>{if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='s'){e.preventDefault();save()}});
</script></body></html>
"""
