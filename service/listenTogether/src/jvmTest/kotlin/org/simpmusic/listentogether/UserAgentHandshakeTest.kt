package org.simpmusic.listentogether

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Real Ktor transport against a local HTTP/WebSocket fixture. Never contacts a public server. */
class UserAgentHandshakeTest {
    private fun headers(socket: Socket): Pair<String, Map<String, String>> {
        val input = socket.getInputStream()
        fun line(): String {
            val bytes = mutableListOf<Byte>()
            while (true) { val b = input.read(); check(b >= 0); if (b == 10) break; if (b != 13) bytes += b.toByte() }
            return bytes.toByteArray().toString(Charsets.UTF_8)
        }
        val first = line()
        val values = mutableMapOf<String, String>()
        while (true) { val next = line(); if (next.isEmpty()) break; values[next.substringBefore(':').lowercase()] = next.substringAfter(':').trim() }
        repeat(values["content-length"]?.toIntOrNull() ?: 0) { input.read() }
        return first to values
    }
    private fun sendFrame(socket: Socket, json: String) {
        val data = json.toByteArray()
        val out = socket.getOutputStream()
        out.write(129)
        if (data.size < 126) out.write(data.size) else { out.write(126); out.write(data.size shr 8); out.write(data.size and 255) }
        out.write(data); out.flush()
    }
    private fun readFrame(socket: Socket): JsonObject {
        val input = socket.getInputStream()
        check(input.read() == 129)
        val second = input.read(); var length = second and 127
        if (length == 126) length = (input.read() shl 8) + input.read()
        val mask = input.readNBytes(4); val bytes = input.readNBytes(length)
        for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor mask[i % 4].toInt()).toByte()
        return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }
    @Test fun authenticatedJsonTransportAndLocalBufferReady() = runBlocking {
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 10_000
            val session = ListenTogetherSession(accessToken = { "account-secret" }, baseUrl = "http://127.0.0.1:${server.localPort}")
            try {
                session.createRoom("")
                server.accept().use { http ->
                    http.soTimeout = 10_000
                    val (request, h) = headers(http)
                    assertEquals("POST /api/jam/rooms HTTP/1.1", request)
                    assertEquals("Bearer account-secret", h["authorization"])
                    assertEquals("Killmon Music/0.7.13", h["user-agent"])
                    val body = """{"code":"ABC234"}"""
                    http.getOutputStream().write("HTTP/1.1 201 Created\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                }
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val (request, h) = headers(socket)
                    assertEquals("GET /api/jam/ws HTTP/1.1", request)
                    assertEquals("Killmon Music/0.7.13", h["user-agent"])
                    val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((h.getValue("sec-websocket-key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                    socket.getOutputStream().write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                    val auth = readFrame(socket)
                    assertEquals("auth", auth.string("type")); assertEquals("account-secret", auth.string("token"))
                    assertEquals("ABC234", auth.string("code")); assertEquals(true, auth.getValue("caps").jsonObject.getValue("ready").jsonPrimitive.boolean)
                    // The backend awaits the account query during auth: no ping may race it.
                    socket.soTimeout = 250
                    assertFailsWith<java.net.SocketTimeoutException> { readFrame(socket) }
                    socket.soTimeout = 10_000
                    sendFrame(socket, """{"type":"welcome","you":"u","state":{"code":"ABC234","host_id":"u","seq":1,"phase":"loading","playing":false,"position":0,"server_time":1700000000000,"current_qid":1,"queue":[{"qid":1,"videoId":"dQw4w9WgXcQ","title":"Test"}],"members":[{"id":"u","name":"Host","connected":true,"loading":true}],"gate":{"waiting":["u"],"timed_out":false}}}""")
                    withTimeout(5000) { while (!session.state.value.inRoom) delay(10) }
                    assertTrue(session.state.value.isHost); assertTrue(session.state.value.loading)
                    session.reportBufferReady("dQw4w9WgXcQ", 0) // Stale generation must send nothing.
                    session.reportBufferReady("wrong-video", 1)
                    session.reportBufferReady("dQw4w9WgXcQ", 1)
                    var message = readFrame(socket)
                    while (message.string("type") == "ping") message = readFrame(socket)
                    // Ready is driven by the local player; no audio/token request precedes it.
                    assertEquals("ready", message.string("type"))
                    assertEquals(1L, message.long("seq"))
                    session.suggestTrack(TrackInfo(id = "dQw4w9WgXcQ"))
                    message = readFrame(socket)
                    while (message.string("type") == "ping") message = readFrame(socket)
                    assertEquals("queue_add", message.string("type"))
                    assertNull(message["play"]) // enqueue alone must preserve the current track
                    session.suggestTrack(TrackInfo(id = "aaaaaaaaaaa"), play = true)
                    message = readFrame(socket)
                    while (message.string("type") == "ping") message = readFrame(socket)
                    assertEquals("queue_add", message.string("type"))
                    assertTrue(message.getValue("play").jsonPrimitive.boolean)
                    assertEquals("aaaaaaaaaaa", message.getValue("track").jsonObject.string("videoId"))
                    session.contribute(listOf(TrackInfo(id = "aaaaaaaaaaa"), TrackInfo(id = "aaaaaaaaaaa"), TrackInfo(id = "bad")))
                    message = readFrame(socket)
                    while (message.string("type") == "ping") message = readFrame(socket)
                    assertEquals("queue_contribute", message.string("type"))
                    assertEquals(1, message.getValue("tracks").jsonArray.size)
                    session.transferHost("guest")
                    message = readFrame(socket)
                    while (message.string("type") == "ping") message = readFrame(socket)
                    assertEquals("transfer_host", message.string("type"))
                    assertEquals("guest", message.string("user_id"))
                    session.command("pos") { put("seq", 1); put("position", 12.5); put("t", 1700000000000L) }
                    message = readFrame(socket)
                    while (message.string("type") == "ping") message = readFrame(socket)
                    assertEquals("pos", message.string("type"))
                    assertEquals(12.5, message.getValue("position").jsonPrimitive.double)
                    assertEquals(1L, message.long("seq"))
                    session.moveTrack(7, 0)
                    message = readFrame(socket)
                    while (message.string("type") == "ping") message = readFrame(socket)
                    assertEquals("queue_move", message.string("type"))
                    assertEquals(7L, message.long("qid")); assertEquals(0L, message.long("to"))
                    session.removeTrack(7)
                    message = readFrame(socket)
                    while (message.string("type") == "ping") message = readFrame(socket)
                    assertEquals("queue_remove", message.string("type"))
                    assertEquals(7L, message.long("qid"))
                    session.markPlaybackFailed()
                    assertTrue(session.state.value.members.single().playbackFailed)
                    sendFrame(socket, """{"type":"state","state":{"code":"ABC234","host_id":"u","seq":1,"phase":"loading","playing":false,"position":0,"server_time":1700000021000,"current_qid":1,"queue":[{"qid":1,"videoId":"dQw4w9WgXcQ"}],"members":[{"id":"u","connected":true,"loading":true}],"gate":{"waiting":["u"],"timed_out":true}}}""")
                    message = readFrame(socket)
                    while (message.string("type") == "ping") message = readFrame(socket)
                    assertEquals("force_play", message.string("type"))
                    assertTrue(session.state.value.error.orEmpty().contains("continuará"))
                    assertTrue(session.state.value.members.single().playbackFailed)
                    session.clearPlaybackFailed()
                    assertFalse(session.state.value.members.single().playbackFailed)

                }
                // The same seq needs a fresh ready after S2 resets this member on reconnect.
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val (request, h) = headers(socket)
                    assertEquals("GET /api/jam/ws HTTP/1.1", request)
                    val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((h.getValue("sec-websocket-key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                    socket.getOutputStream().write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                    val auth = readFrame(socket)
                    assertEquals("auth", auth.string("type"))
                    assertEquals("ABC234", auth.string("code"))
                    sendFrame(socket, """{"type":"welcome","you":"u","state":{"code":"ABC234","host_id":"other","seq":1,"phase":"loading","playing":false,"position":0,"server_time":1700000022000,"current_qid":1,"queue":[{"qid":1,"videoId":"dQw4w9WgXcQ"}],"members":[],"gate":{"waiting":["u"],"timed_out":true}}}""")
                    withTimeout(5000) { while (!session.state.value.isConnected || session.state.value.isHost) delay(10) }
                    session.reportBufferReady("dQw4w9WgXcQ", 1)
                    var message = readFrame(socket)
                    while (message.string("type") == "ping") message = readFrame(socket)
                    // A guest cannot auto-force, even when the timeout has expired.
                    assertEquals("ready", message.string("type"))
                    assertEquals(1L, message.long("seq"))
                }
            } finally { session.release() }
        }
    }
}
