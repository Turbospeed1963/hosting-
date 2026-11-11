package com.example.bothost

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val Green = Color(0xFF4CB98D)
private val Red = Color(0xFFE5766C)
private val Muted = Color(0xFF93A099)
private val DarkColors = darkColorScheme(
    primary = Green, onPrimary = Color(0xFF08150F),
    background = Color(0xFF0F1412), onBackground = Color(0xFFE6ECE8),
    surface = Color(0xFF182019), onSurface = Color(0xFFE6ECE8),
    surfaceVariant = Color(0xFF0C110E), onSurfaceVariant = Muted,
    outline = Color(0xFF2B352F), error = Red
)

class Api(val base: String, val key: String) {
    @Volatile private var token: String? = null

    private fun http(path: String, method: String, body: JSONObject?, bearer: String?): Pair<Int, String> {
        val c = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10000
        c.readTimeout = 20000
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
        if (method == "POST" || method == "PUT") {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write((body?.toString() ?: "{}").toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        return code to (stream?.bufferedReader()?.readText() ?: "")
    }

    /** Admin login: exchanges the admin key for a session token. */
    fun login() {
        val (code, text) = http("/api/login", "POST", JSONObject().put("key", key), null)
        if (code == 429) error("Too many wrong keys. Try again in a few minutes.")
        if (code != 200) error("Wrong admin key")
        token = JSONObject(text).getString("token")
    }

    fun call(path: String, method: String = "GET", body: JSONObject? = null): String {
        if (token == null) login()
        var (code, text) = http(path, method, body, token)
        if (code == 401) {
            login()
            val r = http(path, method, body, token)
            code = r.first; text = r.second
        }
        if (code !in 200..299) error("HTTP $code")
        return text
    }

    suspend fun io(path: String, method: String = "GET", body: JSONObject? = null) =
        withContext(Dispatchers.IO) { call(path, method, body) }
}

/** Reads a picked file (bot.py / requirements.txt) as text. */
fun readUri(ctx: Context, uri: Uri): String? =
    try {
        ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?.takeIf { it.length <= 1_000_000 }
    } catch (e: Exception) { null }

fun fileName(ctx: Context, uri: Uri): String {
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) return c.getString(i)
    }
    return uri.lastPathSegment ?: "file"
}

fun fmtBytes(b: Long): String =
    if (b >= 1L shl 30) "%.1f GB".format(b / 1073741824.0) else "%.0f MB".format(b / 1048576.0)

fun topicFor(key: String): String {
    val h = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        .joinToString("") { "%02x".format(it) }
    return "bh-" + h.take(32)
}

// Finds the server's current link, which run.py publishes by itself
suspend fun discover(key: String): String? = withContext(Dispatchers.IO) {
    try {
        val c = URL("https://ntfy.sh/${topicFor(key)}/json?poll=1&since=all").openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        var last: String? = null
        c.inputStream.bufferedReader().forEachLine { l ->
            try {
                val o = JSONObject(l)
                if (o.optString("event") == "message") last = o.getString("message").trim()
            } catch (_: Exception) {}
        }
        // Only accept a Cloudflare tunnel link (quick or our fixed domain), so a forged message can't redirect the key elsewhere
        last?.takeIf { Regex("""https://(?:[\w-]+\.trycloudflare\.com|dexdev\.ccwu\.cc)/?""").matches(it) }
    } catch (e: Exception) { null }
}

data class Bot(val id: String, val name: String, val status: String, val cpu: Double, val mem: Long)

fun statusLabel(s: String) = when (s) { "running" -> "Running"; "starting" -> "Starting..."; else -> "Stopped" }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = DarkColors) { App() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val prefs = LocalContext.current.getSharedPreferences("host", 0)
    var api by remember {
        val u = prefs.getString("url", null)
        val k = prefs.getString("key", null)
        mutableStateOf(if (u != null && k != null) Api(u, k) else null)
    }
    var screen by remember { mutableStateOf("list") }
    var online by remember { mutableStateOf<Boolean?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(api) {
        online = null
        val a = api ?: return@LaunchedEffect
        while (true) {
            online = try { a.io("/api/ping"); true } catch (e: Exception) { false }
            if (online == false) {
                val u = discover(a.key)
                if (u != null && u != a.base) {
                    prefs.edit().putString("url", u).apply()
                    api = Api(u, a.key)
                    return@LaunchedEffect
                }
            }
            delay(5000)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Text("Bot Host", Modifier.padding(24.dp), style = MaterialTheme.typography.headlineSmall)
                listOf("list" to "My bots", "add" to "Host a bot", "settings" to "Server & admin key").forEach { (id, label) ->
                    NavigationDrawerItem(
                        label = { Text(label) },
                        selected = screen == id,
                        onClick = { screen = id; scope.launch { drawer.close() } },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Bot Host")
                            Spacer(Modifier.width(14.dp))
                            StatusLight(online)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    }
                )
            }
        ) { pad ->
            Surface(Modifier.fillMaxSize().padding(pad)) {
                Box(Modifier.padding(16.dp)) {
                    val a = api
                    if (a == null) {
                        Connect(prefs) { api = it; screen = "list" }
                    } else if (screen == "settings") {
                        Settings(prefs, a, onKey = { api = it }, onLogout = {
                            prefs.edit().clear().apply(); api = null; screen = "list"
                        })
                    } else if (screen == "add") {
                        AddBot(a) { screen = "bot:$it" }
                    } else if (screen.startsWith("bot:")) {
                        BotScreen(a, screen.removePrefix("bot:")) { screen = "list" }
                    } else {
                        BotList(a, online) { screen = "bot:$it" }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusLight(online: Boolean?) {
    val color = when (online) { true -> Green; false -> Red; null -> Muted }
    val label = when (online) { true -> "Connected"; false -> "Offline"; null -> "Checking" }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, color = color)
    }
}

/** Admin login screen: enter the admin key printed by run.py. */
@Composable
fun Connect(prefs: SharedPreferences, onConnect: (Api) -> Unit) {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Admin login", style = MaterialTheme.typography.headlineSmall)
        Text("Enter the admin key shown when you start run.py on your server.", color = Muted)
        OutlinedTextField(key, { key = it }, label = { Text("Admin key") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth())
        result?.let { Text(it, color = Red) }
        Button(enabled = key.isNotBlank() && !busy, onClick = {
            scope.launch {
                busy = true
                result = "Connecting..."
                val k = key.trim()
                val u = discover(k)
                if (u == null) {
                    result = "Server not found. Check the key and that run.py is running."
                } else {
                    try {
                        val a = Api(u, k)
                        a.io("/api/ping")   // logs in with the admin key
                        prefs.edit().putString("url", u).putString("key", k).apply()
                        onConnect(a)
                    } catch (e: Exception) {
                        result = e.message ?: "Login failed."
                    }
                }
                busy = false
            }
        }) { Text(if (busy) "Logging in..." else "Login") }
    }
}

@Composable
fun Settings(prefs: SharedPreferences, api: Api, onKey: (Api) -> Unit, onLogout: () -> Unit) {
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<JSONObject?>(null) }
    var newKey by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(api) {
        while (true) {
            try { stats = JSONObject(api.io("/api/stats")) } catch (_: Exception) {}
            delay(3000)
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Server", style = MaterialTheme.typography.headlineSmall)
        ServerStats(stats)
        Text("Admin key", style = MaterialTheme.typography.titleMedium)
        Text("Generate a new admin key. The old key stops working and all other logins are signed out.", color = Muted)
        msg?.let { Text(it, color = Red) }
        OutlinedButton(onClick = {
            scope.launch {
                msg = null
                try {
                    val k = JSONObject(api.io("/api/admin/key", "POST")).getString("key")
                    prefs.edit().putString("key", k).apply()
                    newKey = k
                    onKey(Api(api.base, k))
                } catch (e: Exception) { msg = "Couldn't change the key (${e.message})." }
            }
        }) { Text("Change admin key") }
        TextButton(onClick = onLogout) { Text("Log out", color = Red) }
    }
    newKey?.let { k ->
        AlertDialog(
            onDismissRequest = { newKey = null },
            title = { Text("New admin key") },
            text = { Text("$k\n\nSave it somewhere safe. The app is already using it.", fontFamily = FontFamily.Monospace) },
            confirmButton = { TextButton(onClick = { newKey = null }) { Text("OK") } }
        )
    }
}

@Composable
fun ServerStats(st: JSONObject?) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (st == null || !st.has("cpu")) {
                Text("Reading server usage...", color = Muted)
            } else {
                val cpu = st.getDouble("cpu")
                val mu = st.getLong("mem_used"); val mt = st.getLong("mem_total")
                val du = st.getLong("disk_used"); val dt = st.getLong("disk_total")
                Meter("CPU", (cpu / 100).toFloat(), "%.0f%%".format(cpu))
                Meter("RAM", (mu.toDouble() / mt).toFloat(), "${fmtBytes(mu)} / ${fmtBytes(mt)}")
                Meter("Disk", (du.toDouble() / dt).toFloat(), "${fmtBytes(du)} / ${fmtBytes(dt)}")
                Text(if (st.optBoolean("docker")) "Bots run in Docker containers" else "Docker not found: bots are not isolated",
                    fontSize = 12.sp, color = if (st.optBoolean("docker")) Muted else Red)
            }
        }
    }
}

@Composable
fun Meter(label: String, frac: Float, text: String) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 13.sp)
            Text(text, fontSize = 13.sp, color = Muted)
        }
        LinearProgressIndicator(progress = { frac.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 3.dp))
    }
}

@Composable
fun BotList(api: Api, online: Boolean?, onOpen: (String) -> Unit) {
    var bots by remember { mutableStateOf<List<Bot>>(emptyList()) }
    var stats by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(api) {
        while (true) {
            try {
                val arr = JSONArray(api.io("/api/bots"))
                bots = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Bot(o.getString("id"), o.getString("name"), o.optString("status", "stopped"),
                        o.optDouble("cpu", 0.0), o.optLong("mem", 0))
                }
                stats = JSONObject(api.io("/api/stats"))
            } catch (_: Exception) {}
            delay(3000)
        }
    }
    Column {
        ServerStats(stats)
        Spacer(Modifier.height(12.dp))
        Text("Your bots", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        if (online == false) {
            Text("Can't reach the server. Open the menu and check Server.", color = Red)
        } else if (bots.isEmpty()) {
            Text("No bots yet. Open the menu and choose Host a bot.", color = Muted)
        }
        LazyColumn {
            items(bots) { b ->
                val on = b.status != "stopped"
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(b.id) }.padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(b.name)
                        if (b.status == "running") {
                            Text("CPU %.1f%%  ·  RAM %s".format(b.cpu, fmtBytes(b.mem)), fontSize = 12.sp, color = Muted)
                        }
                    }
                    Text(statusLabel(b.status), color = if (on) Green else Muted)
                }
                HorizontalDivider()
            }
        }
    }
}

/** Shows a "choose file" row and the picked file's name. */
@Composable
fun FilePick(label: String, picked: String?, onPick: (name: String, text: String) -> Unit) {
    val ctx = LocalContext.current
    var err by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = readUri(ctx, uri)
            if (text == null) err = "Couldn't read that file (text files up to 1 MB)."
            else { err = null; onPick(fileName(ctx, uri), text) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { launcher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (picked == null) label else "$label  ·  $picked")
        }
        err?.let { Text(it, color = Red, fontSize = 12.sp) }
    }
}

@Composable
fun Packages(list: List<String>?) {
    if (list == null) return
    Text(
        if (list.isEmpty()) "No extra packages needed." else "Will auto-install: " + list.joinToString(", "),
        fontSize = 13.sp, color = Muted
    )
}

@Composable
fun AddBot(api: Api, onDone: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var code by remember { mutableStateOf<String?>(null) }
    var codeName by remember { mutableStateOf<String?>(null) }
    var req by remember { mutableStateOf("") }
    var reqName by remember { mutableStateOf<String?>(null) }
    var packages by remember { mutableStateOf<List<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = Regex("""\d{6,}:[\w-]{30,}""").matches(token.trim())

    LaunchedEffect(code, req) {
        val c = code ?: return@LaunchedEffect
        packages = try {
            val arr = JSONObject(api.io("/api/detect", "POST", JSONObject().put("code", c).put("requirements", req)))
                .getJSONArray("packages")
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { null }
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Host a bot", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(name, { name = it }, label = { Text("Bot name") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it }, label = { Text("Bot token from @BotFather") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            supportingText = { if (token.isNotBlank() && !valid) Text("This doesn't look like a bot token.") },
            modifier = Modifier.fillMaxWidth())
        FilePick("Choose bot.py", codeName) { n, t -> codeName = n; code = t }
        FilePick("requirements.txt (optional)", reqName) { n, t -> reqName = n; req = t }
        Packages(packages)
        error?.let { Text(it, color = Red) }
        Button(enabled = valid && code != null && !busy, onClick = {
            scope.launch {
                busy = true
                error = null
                try {
                    val body = JSONObject().put("name", name.ifBlank { "My bot" }).put("token", token.trim())
                    val id = JSONObject(api.io("/api/bots", "POST", body)).getString("id")
                    api.io("/api/bots/$id", "PUT", JSONObject().put("code", code).put("requirements", req))
                    api.io("/api/bots/$id/start", "POST")
                    onDone(id)
                } catch (e: Exception) {
                    error = "Couldn't host the bot (${e.message}). Check the server connection."
                }
                busy = false
            }
        }) { Text(if (busy) "Hosting..." else "Host bot") }
    }
}

@Composable
fun BotScreen(api: Api, id: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("stopped") }
    var log by remember { mutableStateOf("") }
    var cpu by remember { mutableStateOf(0.0) }
    var mem by remember { mutableStateOf(0L) }
    var memLimit by remember { mutableStateOf(0L) }
    var packages by remember { mutableStateOf<List<String>?>(null) }
    var newCode by remember { mutableStateOf<String?>(null) }
    var newCodeName by remember { mutableStateOf<String?>(null) }
    var newReq by remember { mutableStateOf<String?>(null) }
    var newReqName by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val on = status != "stopped"

    LaunchedEffect(id) {
        try {
            val o = JSONObject(api.io("/api/bots/$id"))
            name = o.getString("name"); token = o.getString("token")
            val arr = o.getJSONArray("packages")
            packages = (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {}
        while (true) {
            try {
                val o = JSONObject(api.io("/api/bots/$id/logs"))
                status = o.optString("status", "stopped")
                log = o.getString("log")
                cpu = o.optDouble("cpu", 0.0)
                mem = o.optLong("mem", 0)
                memLimit = o.optLong("mem_limit", 0)
            } catch (_: Exception) {}
            delay(3000)
        }
    }

    fun act(a: String) = scope.launch {
        busy = true
        msg = null
        try { status = JSONObject(api.io("/api/bots/$id/$a", "POST")).optString("status", status) }
        catch (e: Exception) { msg = "Action failed (${e.message})." }
        busy = false
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("All bots") }
        Text(name.ifBlank { "Bot" }, style = MaterialTheme.typography.headlineSmall)
        Text(statusLabel(status), color = if (on) Green else Muted)
        if (status == "running") {
            Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Meter("CPU", (cpu / 100).toFloat(), "%.1f%%".format(cpu))
                    if (memLimit > 0) Meter("RAM", (mem.toDouble() / memLimit).toFloat(), "${fmtBytes(mem)} / ${fmtBytes(memLimit)}")
                    else Text("RAM ${fmtBytes(mem)}", fontSize = 13.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { act("start") }, enabled = !busy && !on) { Text("Start") }
            OutlinedButton(onClick = { act("stop") }, enabled = !busy && on) { Text("Stop") }
            OutlinedButton(onClick = { act("restart") }, enabled = !busy) { Text("Restart") }
        }
        msg?.let { Text(it, color = Red) }
        Packages(packages)
        Text("Logs", style = MaterialTheme.typography.titleMedium)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
            Text(log.ifBlank { "No output yet." }, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp))
        }
        OutlinedButton(onClick = { edit = !edit }) { Text(if (edit) "Hide settings" else "Edit token and files") }
        if (edit) {
            OutlinedTextField(name, { name = it }, label = { Text("Bot name") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it }, label = { Text("Bot token") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth())
            FilePick("Replace bot.py", newCodeName) { n, t -> newCodeName = n; newCode = t }
            FilePick("Replace requirements.txt", newReqName) { n, t -> newReqName = n; newReq = t }
            Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        val body = JSONObject().put("name", name).put("token", token.trim())
                        newCode?.let { body.put("code", it) }
                        newReq?.let { body.put("requirements", it) }
                        api.io("/api/bots/$id", "PUT", body)
                        newCode = null; newCodeName = null; newReq = null; newReqName = null
                        val o = JSONObject(api.io("/api/bots/$id"))
                        val arr = o.getJSONArray("packages")
                        packages = (0 until arr.length()).map { arr.getString(it) }
                        msg = null
                        edit = false
                        // new files or token only take effect after a restart
                        if (on) api.io("/api/bots/$id/restart", "POST")
                    } catch (e: Exception) { msg = "Couldn't save (${e.message})." }
                    busy = false
                }
            }) { Text("Save changes") }
            TextButton(onClick = { confirmDelete = true }) { Text("Delete bot", color = Red) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this bot?") },
            text = { Text("Its container, code and logs are removed from the server.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try { api.io("/api/bots/$id", "DELETE"); onBack() }
                        catch (e: Exception) { msg = "Couldn't delete (${e.message})."; confirmDelete = false }
                    }
                }) { Text("Delete", color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}
