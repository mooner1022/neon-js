package dev.mooner.neonjs.test262

import dev.mooner.neonjs.ExecutionMode
import dev.mooner.neonjs.NeonConsole
import dev.mooner.neonjs.NeonEngine
import dev.mooner.neonjs.net.NetExtension
import dev.mooner.neonjs.net.NetOptions
import dev.mooner.neonjs.node.NodeExtension
import dev.mooner.neonjs.node.NodeOptions
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * discord.js end to end: the bot of `neonjs-test262/discord` (bot.js, bundled by its build.mjs) runs with neonjs-node
 * and neonjs-net and logs in to a mock Discord, REST and gateway over TLS through the network policy. The mock checks
 * the token both carry, sends READY and the guild, waits for the bot's presence update (its sign of being ready) and
 * a heartbeat, then posts "!ping", whose reply must reach REST; "!quit" makes the bot destroy its client, after which
 * the event loop must end by itself. Prints how long each part took, from the start of the bundle's evaluation.
 *
 * Usage: `DiscordKt --bundle FILE [--mode interpreter|compiled|adaptive] [--runs N] [--timeout ms] [--cleartext] [-v]`;
 * `--cleartext` serves http and ws instead (MockWebServer's TLS fails on Android 8, whose TLS sessions give no server
 * names), `-v` prints the bot's console and discord.js's debug messages.
 *
 * `DiscordKt --serve --cleartext [--timeout ms] [-v]` only serves the mock, for the bot run by another process (the
 * CLI: `neonjs --node --allow-host localhost --allow-address 127.0.0.0/8,::1/128 --allow-insecure --env
 * DISCORD_TOKEN=… --env DISCORD_API=… bot.js`, with the values it prints), and checks the same once the bot quits.
 */
fun main(args: Array<String>) {
    var bundle: File? = null
    var mode = ExecutionMode.ADAPTIVE
    var runs = 1
    var timeoutMs = 60_000L
    var verbose = false
    var tls = true
    var serve = false
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--bundle" -> bundle = File(args[++i])
            "--mode" -> mode = when (args[++i]) { "compiled" -> ExecutionMode.COMPILED; "interpreter" -> ExecutionMode.INTERPRETER; else -> ExecutionMode.ADAPTIVE }
            "--runs" -> runs = args[++i].toInt()
            "--timeout" -> timeoutMs = args[++i].toLong()
            "--cleartext" -> tls = false
            "--serve" -> serve = true
            "-v" -> verbose = true
            else -> { System.err.println("unknown argument: $a"); exitProcess(2) }
        }
        i++
    }
    if (serve) {
        val problems = MockDiscord(tls, verbose).use { it.serve(timeoutMs) }
        for (p in problems) println("  FAIL: $p")
        println(if (problems.isEmpty()) "discord.js end to end (served): passed" else "discord.js end to end (served): FAILED")
        exitProcess(if (problems.isEmpty()) 0 else 1)
    }
    val source = (bundle ?: run { System.err.println("--bundle FILE is required"); exitProcess(2) }).readText()
    var failed = 0
    for (run in 1..runs) {
        val problems = MockDiscord(tls, verbose).use { it.run(source, mode, timeoutMs, run) }
        if (problems.isNotEmpty()) {
            failed++
            for (p in problems) println("  FAIL: $p")
        }
    }
    println(if (failed == 0) "discord.js end to end: $runs of $runs runs passed" else "discord.js end to end: $failed of $runs runs FAILED")
    // OkHttp's threads must not keep the process alive
    exitProcess(if (failed == 0) 0 else 1)
}

private const val TOKEN = "NeonJS.mock.token"
private const val INTENTS = 1 or 512 or 32768 // Guilds, GuildMessages, MessageContent
private const val BOT = """{"id":"1100000000000000001","username":"neon","discriminator":"0","global_name":null,"avatar":null,"bot":true,"flags":0}"""
private const val USER = """{"id":"1100000000000000002","username":"neonuser","discriminator":"0","global_name":"Neon User","avatar":null}"""
private const val GUILD = "1100000000000000003"
private const val CHANNEL = "1100000000000000004"
private const val TIME = "2026-10-11T00:00:00.000000+00:00"

private class MockDiscord(private val tls: Boolean, private val verbose: Boolean) : AutoCloseable {
    private val server = MockWebServer()
    private val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
    private val problems = CopyOnWriteArrayList<String>()
    private val marks = LinkedHashMap<String, Long>()
    private val seq = AtomicInteger()
    @Volatile private var start = 0L
    @Volatile private var heartbeats = 0
    @Volatile private var ready = false
    @Volatile private var pinged = false
    @Volatile private var gateway: WebSocket? = null
    @Volatile private var reply: String? = null
    private val closed = java.util.concurrent.CountDownLatch(1)

    init {
        if (tls) server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = handle(request)
        }
        server.start()
    }

    private val origin get() = "${server.hostName}:${server.port}"
    private val https get() = if (tls) "https" else "http"
    private val wss get() = if (tls) "wss" else "ws"

    private fun mark(event: String) {
        val ms = (System.nanoTime() - start) / 1_000_000
        synchronized(marks) { marks.putIfAbsent(event, ms) }
        if (verbose) println("  [mock] $ms ms: $event")
    }

    fun run(source: String, mode: ExecutionMode, timeoutMs: Long, run: Int): List<String> {
        // the host's client trusts the mock's certificate; the policy allows only the mock
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val http = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        val net = NetOptions.builder().allowHosts(server.hostName).allowAddresses("127.0.0.0/8", "::1/128").allowInsecure(!tls).client(http).build()
        val env = mutableMapOf("DISCORD_TOKEN" to TOKEN, "DISCORD_API" to "$https://$origin/api")
        if (verbose) env["DISCORD_DEBUG"] = "1"
        val node = NodeOptions.builder().env(env).argv(listOf("bot.js")).build()
        val console = NeonConsole { level, msg ->
            if (level == NeonConsole.Level.ERROR) problems.add("the bot logged an error: $msg")
            if (verbose) println("  [bot] $msg")
        }
        var timings = ""
        NeonEngine.builder().console(console).webGlobals(true).executionMode(mode)
            .extension(NetExtension(net)).extension(NodeExtension(node)).build().use { engine ->
                engine.newContext().use { c ->
                    c.setUncaughtErrorHandler { e, rejection -> problems.add((if (rejection) "unhandled rejection: " else "uncaught: ") + e.message) }
                    start = System.nanoTime()
                    try {
                        c.eval(source, "bot.js")
                        mark("evaluated")
                        if (!c.runEventLoop(timeoutMs)) problems.add("the event loop did not end within $timeoutMs ms")
                        mark("event loop ended")
                        timings = c.eval("JSON.stringify(globalThis.timings)").asString()
                    } catch (e: Exception) {
                        problems.add("the bot threw: $e")
                    }
                }
            }
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        return check("run $run ($mode): bot.js timings $timings")
    }

    /** Serves until the bot closes its gateway connection or [timeoutMs] pass, then checks as [run] does. */
    fun serve(timeoutMs: Long): List<String> {
        start = System.nanoTime()
        println("serving a mock Discord: DISCORD_TOKEN=$TOKEN DISCORD_API=$https://$origin/api")
        System.out.flush()
        if (!closed.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) problems.add("the gateway was still open after $timeoutMs ms")
        return check("served")
    }

    private fun check(label: String): List<String> {
        if (heartbeats == 0) problems.add("no heartbeat")
        val expected = "pong from neon in #general of Neon Guild"
        if (reply != expected) problems.add("the reply was ${reply?.let { "\"$it\"" }}, not \"$expected\"")
        for (event in listOf("gateway/bot", "gateway open", "identify", "ready", "reply", "closed 1000")) {
            if (event !in marks) problems.add("never got to: $event")
        }
        val m = synchronized(marks) { LinkedHashMap(marks) }
        val phases = m.entries.joinToString("; ") { "${it.key} ${it.value}" }
        val roundtrip = if ("ping" in m && "reply" in m) ", !ping to its reply ${m["reply"]!! - m["ping"]!!} ms" else ""
        println("$label; ms from the start: $phases$roundtrip")
        return problems
    }

    private fun handle(request: RecordedRequest): MockResponse {
        val target = request.target
        if (request.headers["Upgrade"].equals("websocket", ignoreCase = true)) {
            if (target != "/?v=10&encoding=json") problems.add("gateway URL $target")
            return MockResponse.Builder().webSocketUpgrade(Gateway()).build()
        }
        if (request.headers["Authorization"] != "Bot $TOKEN") problems.add("${request.method} $target without the bot's token")
        if (request.headers["User-Agent"]?.startsWith("DiscordBot ") != true) problems.add("User-Agent ${request.headers["User-Agent"]}")
        return when {
            request.method == "GET" && target == "/api/v10/gateway/bot" -> {
                mark("gateway/bot")
                json("""{"url":"$wss://$origin","shards":1,"session_start_limit":{"total":1000,"remaining":999,"reset_after":14400000,"max_concurrency":1}}""")
            }
            request.method == "POST" && target == "/api/v10/channels/$CHANNEL/messages" -> {
                mark("reply")
                val body = request.body?.utf8() ?: ""
                val content = Regex("\"content\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)?.groupValues?.get(1)
                if (!body.contains("\"message_reference\":{") || !body.contains("\"message_id\":\"1100000000000000010\"")) problems.add("the reply does not refer to !ping: $body")
                reply = content
                // the bot has answered: tell it to quit
                dispatchEvent("MESSAGE_CREATE", message("1100000000000000012", USER, "!quit"))
                json(message("1100000000000000011", BOT, content ?: ""))
            }
            else -> {
                problems.add("unexpected request ${request.method} $target")
                json("""{"message":"404: Not Found","code":0}""", 404)
            }
        }
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build()

    private fun message(id: String, author: String, content: String) =
        """{"id":"$id","type":0,"channel_id":"$CHANNEL","guild_id":"$GUILD","author":$author,""" +
            """"member":{"roles":[],"joined_at":"$TIME","deaf":false,"mute":false,"flags":0},"content":"$content",""" +
            """"timestamp":"$TIME","edited_timestamp":null,"tts":false,"mention_everyone":false,"mentions":[],""" +
            """"mention_roles":[],"attachments":[],"embeds":[],"pinned":false,"flags":0,"components":[]}"""

    private fun dispatchEvent(type: String, data: String) {
        gateway?.send("""{"op":0,"t":"$type","s":${seq.incrementAndGet()},"d":$data}""")
    }

    private fun maybePing() {
        synchronized(this) {
            if (!ready || heartbeats == 0 || pinged) return
            pinged = true
        }
        mark("ping")
        dispatchEvent("MESSAGE_CREATE", message("1100000000000000010", USER, "!ping"))
    }

    private inner class Gateway : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            mark("gateway open")
            gateway = webSocket
            // a short heartbeat interval, so that a heartbeat and its ACK happen within the test
            webSocket.send("""{"op":10,"s":null,"t":null,"d":{"heartbeat_interval":1000}}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when (Regex("\"op\":(\\d+)").find(text)?.groupValues?.get(1)?.toInt()) {
                1 -> {
                    heartbeats++
                    mark("heartbeat")
                    webSocket.send("""{"op":11,"s":null,"t":null,"d":null}""")
                    maybePing()
                }
                2 -> {
                    mark("identify")
                    if (!text.contains("\"token\":\"$TOKEN\"")) problems.add("IDENTIFY without the token")
                    if (!text.contains("\"intents\":$INTENTS")) problems.add("IDENTIFY with other intents: $text")
                    dispatchEvent("READY", """{"v":10,"user":$BOT,"guilds":[{"id":"$GUILD","unavailable":true}],"session_id":"neon-session",""" +
                        """"resume_gateway_url":"$wss://$origin","shard":[0,1],"application":{"id":"1100000000000000001","flags":0}}""")
                    dispatchEvent("GUILD_CREATE", guild())
                }
                3 -> {
                    mark("ready")
                    if (!text.contains("\"name\":\"1 guild\"") || !text.contains("\"type\":3")) problems.add("presence update $text")
                    ready = true
                    maybePing()
                }
                else -> problems.add("unexpected gateway message $text")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            mark("closed $code")
            closed.countDown()
            webSocket.close(if (code == 1005) 1000 else code, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            problems.add("gateway failure: $t")
        }
    }

    private fun guild() =
        """{"id":"$GUILD","name":"Neon Guild","icon":null,"splash":null,"discovery_splash":null,"owner_id":"1100000000000000002",""" +
            """"afk_channel_id":null,"afk_timeout":300,"verification_level":0,"default_message_notifications":0,"explicit_content_filter":0,""" +
            """"roles":[{"id":"$GUILD","name":"@everyone","color":0,"hoist":false,"icon":null,"unicode_emoji":null,"position":0,""" +
            """"permissions":"1071698660929","managed":false,"mentionable":false,"flags":0}],"emojis":[],"features":[],"mfa_level":0,""" +
            """"application_id":null,"system_channel_id":null,"system_channel_flags":0,"rules_channel_id":null,"vanity_url_code":null,""" +
            """"description":null,"banner":null,"premium_tier":0,"premium_subscription_count":0,"preferred_locale":"en-US",""" +
            """"public_updates_channel_id":null,"nsfw_level":0,"premium_progress_bar_enabled":false,"safety_alerts_channel_id":null,""" +
            """"stickers":[],"joined_at":"$TIME","large":false,"unavailable":false,"member_count":2,"voice_states":[],""" +
            """"members":[{"user":$BOT,"roles":[],"joined_at":"$TIME","deaf":false,"mute":false,"flags":0}],""" +
            """"channels":[{"id":"$CHANNEL","type":0,"guild_id":"$GUILD","name":"general","position":0,"permission_overwrites":[],""" +
            """"topic":null,"nsfw":false,"last_message_id":null,"rate_limit_per_user":0,"parent_id":null}],""" +
            """"threads":[],"presences":[],"stage_instances":[],"guild_scheduled_events":[],"soundboard_sounds":[]}"""

    override fun close() {
        server.close()
    }
}
