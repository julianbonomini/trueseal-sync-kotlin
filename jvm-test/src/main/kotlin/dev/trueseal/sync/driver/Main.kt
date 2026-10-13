package dev.trueseal.sync.driver

import dev.trueseal.sync.ConnectionState
import dev.trueseal.sync.DeliveryIssue
import dev.trueseal.sync.Member
import dev.trueseal.sync.Storage
import dev.trueseal.sync.TrueSeal
import dev.trueseal.sync.TrueSealError
import dev.trueseal.sync.TrueSealEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.PrintWriter

/**
 * The Kotlin Scenario Driver entry point (KOTLIN-2, #10; e2e ADR-0001).
 *
 * trueseal-e2e E2E-1 starts this main over stdin/stdout and drives the
 * canonical [TrueSeal] API through the test-only JSON control protocol —
 * one JSON object per line, requests carry an `id`, responses echo it,
 * asynchronous messages/events/issues are pushed as id-less notifications.
 * The protocol is documented in jvm-test/README.md (v0 draft; E2E-1 owns
 * its evolution — every SDK driver speaks the same shape).
 *
 * Run: `./gradlew :jvm-test:installDist`, then
 * `jvm-test/build/install/jvm-test/bin/jvm-test` (needs the pinned Linux
 * core: `scripts/fetch-core-artifacts.sh --linux`, and
 * `-Djna.library.path=jvm-test/native` — the installDist start script sets
 * it via the `application` config below).
 */
object Main {

    private val json = Json { ignoreUnknownKeys = true }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        Driver(System.`in`.bufferedReader(), PrintWriter(System.out, true)).run()
    }
}

class Driver(
    private val input: BufferedReader,
    private val output: PrintWriter,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val out = Mutex()
    private var session: TrueSeal? = null

    suspend fun run() {
        while (true) {
            val line = input.readLine() ?: break
            if (line.isBlank()) continue
            val request = try {
                json.parseToJsonElement(line).jsonObject
            } catch (t: Throwable) {
                reply(null, false, errorJson("malformed", "not a JSON object: ${t.message}"))
                continue
            }
            val id = request["id"]?.jsonPrimitive?.long
            val command = request["command"]?.jsonPrimitive?.content
            if (command == null) {
                reply(id, false, errorJson("malformed", "missing \"command\""))
                continue
            }
            try {
                val result = execute(command, request)
                reply(id, true, result)
            } catch (e: TrueSealError) {
                reply(id, false, errorJson(e.code, e.message ?: e.toString()))
            } catch (e: IllegalStateException) {
                reply(id, false, errorJson("driver", e.message ?: e.toString()))
            } catch (t: Throwable) {
                reply(id, false, errorJson("driver", t.message ?: t.toString()))
            }
        }
        session?.close()
    }

    private fun requireSession(): TrueSeal =
        session ?: throw IllegalStateException("no open session — send \"open\" first")

    private suspend fun execute(command: String, req: JsonObject): JsonObject = when (command) {
        "open" -> {
            check(session == null) { "a session is already open" }
            val relay = req.getValue("relay").jsonPrimitive.content
            val storagePath = req.getValue("storagePath").jsonPrimitive.content
            val namespace = req["namespace"]?.jsonPrimitive?.content ?: "default"
            val maxPayload = req["maxPayloadBytes"]?.jsonPrimitive?.content?.toUInt()
            val ts = TrueSeal.open(
                relay = relay,
                storage = Storage.directory(storagePath),
                namespace = namespace,
                maxPayloadBytes = maxPayload,
            )
            session = ts
            buildJsonObject { put("me", memberJson(ts.me.value)) }
        }
        "close" -> {
            requireSession().close()
            session = null
            buildJsonObject {}
        }
        "info" -> buildJsonObject {
            val info = TrueSeal.info
            put("sdkVersion", info.sdkVersion)
            put("transportVersion", info.transportVersion.toLong())
            put("endToEndVersion", info.endToEndVersion.toLong())
            put("maxPayloadBytes", TrueSeal.maxPayloadBytes.toLong())
            put("maxGroupSize", TrueSeal.maxGroupSize.toLong())
        }
        "send" -> {
            val bodyHex = req.getValue("bodyHex").jsonPrimitive.content
            val id = requireSession().send(bodyHex.hexToByteArray())
            buildJsonObject { put("messageId", id) }
        }
        "startPairing" -> buildJsonObject { put("token", requireSession().startPairing()) }
        "accept" -> {
            requireSession().accept(req.getValue("requestId").jsonPrimitive.content)
            buildJsonObject {}
        }
        "cancelPairing" -> { requireSession().cancelPairing(); buildJsonObject {} }
        "join" -> {
            requireSession().join(req.getValue("token").jsonPrimitive.content)
            buildJsonObject {}
        }
        "cancelJoin" -> { requireSession().cancelJoin(); buildJsonObject {} }
        "remove" -> {
            requireSession().remove(req.getValue("memberId").jsonPrimitive.content)
            buildJsonObject {}
        }
        "leave" -> { requireSession().leave(); buildJsonObject {} }
        "destroyGroup" -> { requireSession().destroyGroup(); buildJsonObject {} }
        "status" -> buildJsonObject { put("status", requireSession().status.value.code) }
        "me" -> buildJsonObject { put("me", memberJson(requireSession().me.value)) }
        "members" -> buildJsonObject {
            putJsonArray("members") {
                for (m in requireSession().members.value) add(memberJson(m))
            }
        }
        "connection" -> buildJsonObject { put("connection", connectionJson(requireSession().connection.value)) }
        "receive" -> {
            requireSession().onMessage { message ->
                scope.launch {
                    notify(
                        buildJsonObject {
                            put("event", "message")
                            putJsonObject("message") {
                                put("id", message.id)
                                put("from", memberJson(message.from))
                                put("bodyHex", message.body.toHex())
                            }
                        },
                    )
                }
            }
            buildJsonObject {}
        }
        "listenEvents" -> {
            requireSession().onEvent { event ->
                scope.launch { notify(eventJson(event)) }
            }
            buildJsonObject {}
        }
        "listenIssues" -> {
            requireSession().onDeliveryIssue { issue ->
                scope.launch { notify(issueJson(issue)) }
            }
            buildJsonObject {}
        }
        else -> throw IllegalStateException("unknown command \"$command\"")
    }

    private suspend fun reply(id: Long?, ok: Boolean, payload: JsonObject) {
        val response = buildJsonObject {
            if (id != null) put("id", id)
            put("ok", ok)
            put(if (ok) "result" else "error", payload)
        }
        out.withLock { output.println(response.toString()) }
    }

    private suspend fun notify(payload: JsonObject) {
        out.withLock { output.println(payload.toString()) }
    }

    private fun errorJson(code: String, message: String): JsonObject = buildJsonObject {
        put("code", code)
        put("message", message)
    }

    private fun memberJson(m: Member): JsonObject = buildJsonObject {
        put("id", m.id)
        put("name", m.name)
    }

    private fun connectionJson(c: ConnectionState): JsonObject = buildJsonObject {
        put("state", c.code)
        if (c is ConnectionState.RelayVersionUnsupported) {
            put("min", c.min.toLong())
            put("max", c.max.toLong())
        }
    }

    private fun eventJson(e: TrueSealEvent): JsonObject = buildJsonObject {
        put("event", "event")
        when (e) {
            is TrueSealEvent.StatusChanged -> {
                put("kind", "statusChanged")
                put("status", e.status.code)
                put("reason", e.reason.code)
            }
            is TrueSealEvent.MembersChanged -> {
                put("kind", "membersChanged")
                putJsonArray("members") { for (m in e.members) add(memberJson(m)) }
            }
            is TrueSealEvent.JoinRequestReceived -> {
                put("kind", "joinRequest")
                put("requestId", e.request.id)
                put("name", e.request.name)
            }
            is TrueSealEvent.ConnectionChanged -> {
                put("kind", "connectionChanged")
                put("connection", connectionJson(e.connection))
            }
            is TrueSealEvent.AdmissionDropped -> {
                put("kind", "admissionDropped")
                put("name", e.name)
                put("reason", e.reason.code)
            }
        }
    }

    private fun issueJson(i: DeliveryIssue): JsonObject = buildJsonObject {
        put("event", "issue")
        when (i) {
            is DeliveryIssue.Unreadable -> { put("kind", "unreadable"); put("reason", i.reason.code) }
            is DeliveryIssue.Unauthorized -> put("kind", "unauthorized")
            is DeliveryIssue.HeldForUpgrade -> { put("kind", "heldForUpgrade"); put("version", i.version.toLong()) }
            is DeliveryIssue.HandlerGaveUp -> { put("kind", "handlerGaveUp"); put("messageId", i.messageId); put("error", i.error) }
            is DeliveryIssue.SendFailed -> { put("kind", "sendFailed"); put("messageId", i.messageId); put("reason", i.reason.code) }
        }
    }
}

private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "hex body must have an even length" }
    return ByteArray(length / 2) { i ->
        ((Character.digit(this[2 * i], 16) shl 4) or Character.digit(this[2 * i + 1], 16)).toByte()
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
