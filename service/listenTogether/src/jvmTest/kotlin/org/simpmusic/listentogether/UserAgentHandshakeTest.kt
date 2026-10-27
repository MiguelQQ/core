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
    @Test fun authenticatedJsonTransportAndS2Token() = runBlocking {
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
                    assertEquals("Killmon Music/0.7.11", h["user-agent"])
                    val body = """{"code":"ABC234"}"""
                    http.getOutputStream().write("HTTP/1.1 201 Created\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                }
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val (request, h) = headers(socket)
                    assertEquals("GET /api/jam/ws HTTP/1.1", request)
                    assertEquals("Killmon Music/0.7.11", h["user-agent"])
                    val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((h.getValue("sec-websocket-key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                    socket.getOutputStream().write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                    val auth = readFrame(socket)
                    assertEquals("auth", auth.string("type")); assertEquals("account-secret", auth.string("token"))
                    assertEquals("ABC234", auth.string("code")); assertEquals(true, auth.getValue("caps").jsonObject.getValue("ready").jsonPrimitive.boolean)
                    sendFrame(socket, """{"type":"welcome","you":"u","state":{"code":"ABC234","host_id":"u","seq":1,"phase":"loading","playing":false,"position":0,"server_time":1700000000000,"queue":[],"members":[],"gate":{"waiting":["u"],"timed_out":false}}}""")
                    withTimeout(5000) { while (!session.state.value.inRoom) delay(10) }
                    assertTrue(session.state.value.isHost); assertTrue(session.state.value.loading)
                    val url = async(Dispatchers.Default) { session.audioUrl("dQw4w9WgXcQ") }
                    var message = readFrame(socket)
                    while (message.string("type") == "ping") message = readFrame(socket)
                    assertEquals("audio", message.string("type"))
                    sendFrame(socket, """{"type":"audio","video_id":"dQw4w9WgXcQ","path":"/api/jam/audio/ABC234/dQw4w9WgXcQ","token":"short-token"}""")
                    assertEquals("http://127.0.0.1:${server.localPort}/api/jam/audio/ABC234/dQw4w9WgXcQ?t=short-token", withTimeout(5000) { url.await() })
                }
            } finally { session.release() }
        }
    }
}
