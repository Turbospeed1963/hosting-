package com.example.bothost

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

private const val STARTER = """import os
from telegram import Update
from telegram.ext import Application, CommandHandler, ContextTypes

async def start(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    await update.message.reply_text("Hello! The bot is running.")

app = Application.builder().token(os.environ["BOT_TOKEN"]).build()
app.add_handler(CommandHandler("start", start))
app.run_polling()
"""

class Api(val base: String, val key: String) {
    fun call(path: String, method: String = "GET", body: JSONObject? = null): String {
        val c = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Authorization", "Bearer $key")
        if (method == "POST" || method == "PUT") {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write((body?.toString() ?: "{}").toByteArray()) }
        }
        if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().readText()
    }

    suspend fun io(path: String, method: String = "GET", body: JSONObject? = null) =
        withContext(Dispatchers.IO) { call(path, method, body) }
}

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
        // Only accept a Cloudflare quick-tunnel link, so a forged message can't redirect the key elsewhere
        last?.takeIf { Regex("""https://[\w-]+\.trycloudflare\.com/?""").matches(it) }
    } catch (e: Exception) { null }
}

data class Bot(val id: String, val name: String, val running: Boolean)

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
                listOf("list" to "My bots", "add" to "Host a bot", "settings" to "Server").forEach { (id, label) ->
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
                    if (a == null || screen == "settings") {
                        Connect(prefs, a) { api = it; screen = "list" }
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

@Composable
fun Connect(prefs: SharedPreferences, current: Api?, onConnect: (Api) -> Unit) {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(current?.key ?: "") }
    var result by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connect", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(key, { key = it }, label = { Text("Access key") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth())
        result?.let { Text(it, color = if (ok) Green else Red) }
        Button(enabled = key.isNotBlank(), onClick = {
            scope.launch {
                ok = false
                result = "Connecting..."
                val k = key.trim()
                val u = discover(k)
                if (u == null) {
                    result = "Server not found. Check the key and that run.py is running."
                    return@launch
                }
                try {
                    Api(u, k).io("/api/ping")
                    prefs.edit().putString("url", u).putString("key", k).apply()
                    onConnect(Api(u, k))
                } catch (e: Exception) {
                    result = "Not connected (${e.message})."
                }
            }
        }) { Text("Connect") }
    }
}

@Composable
fun BotList(api: Api, online: Boolean?, onOpen: (String) -> Unit) {
    var bots by remember { mutableStateOf<List<Bot>>(emptyList()) }
    LaunchedEffect(api) {
        while (true) {
            try {
                val arr = JSONArray(api.io("/api/bots"))
                bots = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Bot(o.getString("id"), o.getString("name"), o.getBoolean("running"))
                }
            } catch (_: Exception) {}
            delay(3000)
        }
    }
    Column {
        Text("Your bots", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        if (online == false) {
            Text("Can't reach the server. Open the menu and check Server.", color = Red)
        } else if (bots.isEmpty()) {
            Text("No bots yet. Open the menu and choose Host a bot.", color = Muted)
        }
        LazyColumn {
            items(bots) { b ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(b.id) }.padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(b.name)
                    Text(if (b.running) "Running" else "Stopped", color = if (b.running) Green else Muted)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun AddBot(api: Api, onDone: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var code by remember { mutableStateOf(STARTER) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = Regex("""\d{6,}:[\w-]{30,}""").matches(token.trim())
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Host a bot", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(name, { name = it }, label = { Text("Bot name") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it }, label = { Text("Bot token from @BotFather") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            supportingText = { if (token.isNotBlank() && !valid) Text("This doesn't look like a bot token.") },
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(code, { code = it }, label = { Text("bot.py") }, minLines = 8,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = Red) }
        Button(enabled = valid && !busy, onClick = {
            scope.launch {
                busy = true
                error = null
                try {
                    val body = JSONObject().put("name", name.ifBlank { "My bot" }).put("token", token.trim())
                    val id = JSONObject(api.io("/api/bots", "POST", body)).getString("id")
                    api.io("/api/bots/$id", "PUT", JSONObject().put("code", code))
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
    var code by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        try {
            val o = JSONObject(api.io("/api/bots/$id"))
            name = o.getString("name"); token = o.getString("token"); code = o.getString("code")
        } catch (_: Exception) {}
        while (true) {
            try {
                val o = JSONObject(api.io("/api/bots/$id/logs"))
                running = o.getBoolean("running")
                log = o.getString("log")
            } catch (_: Exception) {}
            delay(3000)
        }
    }

    fun act(a: String) = scope.launch {
        busy = true
        msg = null
        try { api.io("/api/bots/$id/$a", "POST"); running = a != "stop" }
        catch (e: Exception) { msg = "Action failed (${e.message})." }
        busy = false
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("All bots") }
        Text(name.ifBlank { "Bot" }, style = MaterialTheme.typography.headlineSmall)
        Text(if (running) "Running" else "Stopped", color = if (running) Green else Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { act("start") }, enabled = !busy && !running) { Text("Start") }
            OutlinedButton(onClick = { act("stop") }, enabled = !busy && running) { Text("Stop") }
            OutlinedButton(onClick = { act("restart") }, enabled = !busy) { Text("Restart") }
        }
        msg?.let { Text(it, color = Red) }
        Text("Logs", style = MaterialTheme.typography.titleMedium)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
            Text(log.ifBlank { "No output yet." }, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp))
        }
        OutlinedButton(onClick = { edit = !edit }) { Text(if (edit) "Hide settings" else "Edit token and code") }
        if (edit) {
            OutlinedTextField(name, { name = it }, label = { Text("Bot name") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it }, label = { Text("Bot token") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(code, { code = it }, label = { Text("bot.py") }, minLines = 8,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        val body = JSONObject().put("name", name).put("token", token.trim()).put("code", code)
                        api.io("/api/bots/$id", "PUT", body)
                        msg = null
                        edit = false
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
            text = { Text("Its code and logs are removed from the server.") },
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
