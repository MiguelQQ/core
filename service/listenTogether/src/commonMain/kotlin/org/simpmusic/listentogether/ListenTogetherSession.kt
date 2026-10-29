package org.simpmusic.listentogether

import com.maxrave.ktorext.getEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import kotlin.time.TimeSource
import kotlin.coroutines.CoroutineContext

/** Where the socket is, independent of whether a room has been joined. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState

    data object Connecting : ConnectionState

    data class Connected(val serverVersion: String) : ConnectionState

    /** Retries are exhausted or the server refused us; the user has to act. */
    data class Failed(val reason: String) : ConnectionState
}

/** One person in the room, as the UI needs them. */
data class RoomMember(
    val userId: String,
    val username: String,
    val isHost: Boolean,
    val isConnected: Boolean,
    /** True while this member has not answered `buffer_ready` for the current track. */
    val isBuffering: Boolean = false,
    val playbackFailed: Boolean = false,
)

data class PendingJoin(
    val userId: String,
    val username: String,
)

data class PendingSuggestion(
    val suggestionId: String,
    val fromUsername: String,
    val track: TrackInfo,
)

data class ListenTogetherState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    /** Null until a room is created or joined. */
    val roomCode: String? = null,
    val selfUserId: String = "",
    val isHost: Boolean = false,
    val members: List<RoomMember> = emptyList(),
    val joinRequests: List<PendingJoin> = emptyList(),
    val suggestions: List<PendingSuggestion> = emptyList(),
    val currentTrack: TrackInfo? = null,
    val isPlaying: Boolean = false,
    val position: Long = 0L,
    /**
     * The room's queue, in the host's order.
     *
     * Without this a guest only ever knows the *current* track, so the moment it ends they carry
     * on with whatever their own player had queued — which is everyone listening to something
     * different one track later.
     */
    val queue: List<TrackInfo> = emptyList(),
    /** Non-empty while the room is held at the buffer barrier. */
    val waitingFor: List<String> = emptyList(),
    /** Server time the last transport command was captured at; 0 when unknown. */
    val lastActionServerTime: Long = 0L,
    /**
     * The code we asked to join and have not heard back about.
     *
     * Joining is not immediate — the host has to approve it — and without this the UI has nothing
     * to show between sending `join_room` and `join_approved` arriving, so a wrong code and a host
     * who simply has not looked at their phone are indistinguishable: both look like nothing
     * happened.
     */
    val pendingJoinCode: String? = null,
    /** Set once and cleared by the UI, so a transient failure cannot wedge the screen. */
    val error: String? = null,
    val seq: Long = 0,
    val loading: Boolean = false,
    val gateTimedOut: Boolean = false,
    val queueIds: List<Long> = emptyList(),
    val queueContributors: List<String> = emptyList(),
    /** Parallel to [queue]: true when the member who added the track picked its video. */
    val queueIsVideo: List<Boolean> = emptyList(),
    /**
     * Parallel to [queue]. False when a person added the track (the manual block). True for the
     * automatic mix. The server owns the order; this flag must survive decode so a recompose cannot
     * treat a hand-picked song as mix filler.
     */
    val queueAutomatic: List<Boolean> = emptyList(),
) {
    val inRoom: Boolean get() = roomCode != null
    val isConnected: Boolean get() = connection is ConnectionState.Connected

    /** Names, not ids — "Waiting for Long" is the only useful phrasing of the barrier. */
    val waitingForNames: List<String>
        get() = waitingFor.mapNotNull { id -> members.firstOrNull { it.userId == id }?.username }
}

/** Killmon JAM JSON transport. Account credentials are read asynchronously for each connection. */
class ListenTogetherSession internal constructor(
    private val accessToken: suspend () -> String?,
    private val baseUrl: String,
    dispatcher: CoroutineContext = Dispatchers.Default,
) : CoroutineScope {
    // Production callers cannot override the room server. Local fixtures use the internal constructor.
    constructor(accessToken: suspend () -> String?, dispatcher: CoroutineContext = Dispatchers.Default) :
        this(accessToken, BASE, dispatcher)

    override val coroutineContext = SupervisorJob() + dispatcher
    private val client = HttpClient(getEngine()) {
        install(io.ktor.client.plugins.UserAgent) { agent = "Killmon Music/0.7.27" }
        install(WebSockets)
        install(HttpTimeout) { connectTimeoutMillis = 15_000; requestTimeoutMillis = 20_000 }
    }
    private val _state = MutableStateFlow(ListenTogetherState())
    val state = _state.asStateFlow()
    private var socket: DefaultClientWebSocketSession? = null
    private var connection: Job? = null
    private val start = TimeSource.Monotonic.markNow()
    private fun elapsed() = start.elapsedNow().inWholeMilliseconds + 1
    private val clock = ServerClock(::elapsed)
    private var pingAt = 0L
    private var forcedSeq = -1L
    private var failedSeq = -1L
    private class Addition(val code: String, val track: TrackInfo, val play: Boolean, val isVideo: Boolean, val after: String?)
    private val additions = Channel<Addition>(Channel.UNLIMITED)
    init {
        launch {
            for (addition in additions) {
                val code = addition.code
                state.first { it.roomCode != code || it.isConnected }
                if (state.value.roomCode != code) continue
                try {
                    send("queue_add") {
                        put("track", encodeJamTrack(addition.track, addition.isVideo))
                        // Hand-picked. The server also forces this; sending it keeps the wire explicit.
                        put("automatic", false)
                        if (addition.play) put("play", true)
                        addition.after?.let { put("after", it) }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { playbackError("No se pudo añadir la canción. Comprueba la conexión con la JAM.") }
                // Keep playlist additions below S2's 60 messages/10 s, leaving room for pos/ping.
                delay(250)
            }
        }
    }
    var autoApproveJoins = true
    var autoApproveSuggestions = true

    fun connect() { _state.update { it.copy(connection = ConnectionState.Connected("Killmon JAM")) } }
    fun createRoom(username: String) = enter(null)
    fun joinRoom(roomCode: String, username: String) = enter(roomCode.trim().uppercase())
    private fun enter(code: String?) {
        connection?.cancel()
        _state.update { it.copy(connection = ConnectionState.Connecting, pendingJoinCode = code, error = null) }
        connection = launch {
            try {
                val token = accessToken() ?: error("Inicia sesión con tu cuenta Killmon")
                val response = client.post("$baseUrl/api/jam/${if (code == null) "rooms" else "join"}") {
                    bearerAuth(token); contentType(ContentType.Application.Json)
                    setBody(obj { if (code != null) put("code", code) }.toString())
                }
                val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                if (response.status.value !in 200..299) error(json["message"]?.jsonPrimitive?.content ?: "No se pudo entrar a la JAM")
                val roomCode = json.getValue("code").jsonPrimitive.content
                runSocket(roomCode)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(connection = ConnectionState.Failed(e.message ?: "Error de conexión"), pendingJoinCode = null, error = e.message) } }
        }
    }
    private suspend fun runSocket(code: String) {
        var attempts = 0
        while (currentCoroutineContext().isActive) {
            try {
                val token = accessToken() ?: error("Inicia sesión con Killmon")
                coroutineScope {
                    val live = client.webSocketSession("${baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")}/api/jam/ws")
                    socket = live
                    clock.reset()
                    forcedSeq = -1L
                    val welcomed = CompletableDeferred<Unit>()
                    try {
                        live.send(Frame.Text(obj { put("type", "auth"); put("token", token); put("code", code); put("caps", obj { put("ready", true) }) }.toString()))
                        val pinger = launch {
                            welcomed.await()
                            while (isActive) { pingAt = elapsed(); send("ping"); delay(3000) }
                        }
                        try {
                            for (frame in live.incoming) {
                                if (frame is Frame.Text) {
                                    val message = Json.parseToJsonElement(frame.readText()).jsonObject
                                    onMessage(message)
                                    if (message.string("type") == "welcome") welcomed.complete(Unit)
                                }
                                if (_state.value.roomCode != null) attempts = 0
                            }
                        } finally { pinger.cancel() }
                    } finally { socket = null; withContext(NonCancellable) { live.close() } }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = "Reconectando a la JAM…") } }
            if (++attempts > 10) error("No se pudo reconectar. Vuelve a entrar a la JAM")
            _state.update { it.copy(connection = ConnectionState.Connecting) }
            delay((attempts * 1000L).coerceAtMost(10_000))
        }
    }
    suspend fun send(type: String, block: JsonObjectBuilder.() -> Unit = {}) {
        socket?.send(Frame.Text(obj { put("type", type); block() }.toString()))
    }
    fun command(type: String, block: JsonObjectBuilder.() -> Unit = {}) = launch {
        try { send(type, block) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { playbackError("Se perdió la conexión con la JAM. Reconectando…") }
    }
    private fun onMessage(m: JsonObject) {
        when (m.string("type")) {
            "welcome" -> { _state.update { it.copy(selfUserId = m.string("you"), error = null) }; applyState(m.getValue("state").jsonObject) }
            "state" -> applyState(m.getValue("state").jsonObject)
            "clock" -> if (m.long("seq") == state.value.seq && !state.value.loading) _state.update { it.copy(position = (m.getValue("position").jsonPrimitive.double * 1000).toLong(), lastActionServerTime = m.long("server_time"), isPlaying = true) }
            "pong" -> { val t = m.long("server_time"); clock.recordPong(pingAt, t, t) }
            "error" -> _state.update { it.copy(error = m.string("message"), pendingJoinCode = null) }
            "closed" -> { disconnect(); _state.update { it.copy(error = "La JAM se cerró") } }
        }
    }
    private fun applyState(s: JsonObject) {
        _state.update { old ->
            val decoded = decodeJamState(s, old)
            decoded.copy(members = decoded.members.map { member ->
                member.copy(playbackFailed = member.userId == decoded.selfUserId && failedSeq == decoded.seq)
            })
        }
        val room = state.value
        if (room.isHost && room.loading && room.gateTimedOut && forcedSeq != room.seq) {
            forcedSeq = room.seq
            playbackError("La espera de carga terminó. La JAM continuará; quienes sigan cargando se unirán cuando estén listos.")
            forcePlay()
        }
    }
    fun markPlaybackFailed() {
        failedSeq = state.value.seq
        _state.update { room -> room.copy(
            error = "Falló la carga de YouTube en este teléfono. La JAM continuará para los demás. Vuelve a entrar para reintentar.",
            members = room.members.map { if (it.userId == room.selfUserId) it.copy(playbackFailed = true) else it },
        ) }
    }
    fun clearPlaybackFailed() {
        failedSeq = -1L
        _state.update { room -> room.copy(members = room.members.map { it.copy(playbackFailed = false) }) }
    }
    fun serverNow() = clock.now()
    fun positionAt(position: Long, isPlaying: Boolean) = clock.positionAt(position, state.value.lastActionServerTime, isPlaying)
    fun reportBufferReady(trackId: String, seq: Long = state.value.seq) {
        val room = state.value
        if (room.isConnected && room.seq == seq && room.currentTrack?.id == trackId) {
            command("ready") { put("seq", seq) }
        }
    }
    fun contribute(tracks: List<TrackInfo>) = command("queue_contribute") {
        put("tracks", JsonArray(tracks.filter { Regex("^[A-Za-z0-9_-]{11}$").matches(it.id) }.distinctBy { it.id }.take(30).map(::encodeJamTrack)))
    }
    fun requestSync() = command("sync")
    fun forcePlay() = command("force_play")
    fun transferHost(userId: String) = command("transfer_host") { put("user_id", userId) }
    fun removeTrack(qid: Long) = command("queue_remove") { put("qid", qid) }
    fun moveTrack(qid: Long, to: Int) = command("queue_move") { put("qid", qid); put("to", to) }
    /**
     * [after] chains a playlist right behind a track just added. Without it a plain add joins the
     * manual FIFO block (ahead of the automatic mix) and a [play] add goes right behind the track
     * that is playing now. [automatic] is always false: only the server's mix is automatic.
     */
    fun suggestTrack(track: TrackInfo, play: Boolean = false, isVideo: Boolean = false, after: String? = null) {
        val code = state.value.roomCode ?: return
        additions.trySend(Addition(code, track, play, isVideo, after))
    }
    fun playbackError(message: String) { _state.update { it.copy(error = message) } }
    fun clearError() { _state.update { it.copy(error = null) } }
    fun cancelJoin() = disconnect()
    fun leaveRoom() { launch { try { send("leave") } finally { disconnect() } } }
    fun disconnect() { failedSeq = -1L; forcedSeq = -1L; connection?.cancel(); connection = null; socket = null; _state.value = ListenTogetherState() }
    fun release() { disconnect(); client.close(); cancel() }
    fun approveJoin(userId: String) = Unit
    fun rejectJoin(userId: String) = Unit
    fun approveSuggestion(suggestionId: String) = Unit
    fun rejectSuggestion(suggestionId: String) = Unit
    fun kickUser(userId: String) { _state.update { it.copy(error = "La expulsión no está disponible") } }
    companion object { const val BASE = "https://killmon.seriepokemon.online" }
}

internal fun obj(block: JsonObjectBuilder.() -> Unit) = buildJsonObject(block)
internal fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.longOrNull ?: 0L
internal fun decodeJamTrack(t: JsonObject) = TrackInfo(id = t.string("videoId"), title = t.string("title"), artist = t.string("artist"), duration = t.long("duration_ms"), thumbnail = t.string("thumbnail"))
internal fun encodeJamTrack(t: TrackInfo, isVideo: Boolean = false) = obj {
    put("videoId", t.id); put("title", t.title); put("artist", t.artist); put("durationMs", t.duration); put("thumbnail", t.thumbnail)
    if (isVideo) put("isVideo", true)
}

internal fun decodeJamState(s: JsonObject, old: ListenTogetherState): ListenTogetherState {
        val queue = s["queue"]?.jsonArray.orEmpty().map { it.jsonObject }
        val host = s.string("host_id")
        val gate = s["gate"] as? JsonObject
        val waiting = gate?.get("waiting")?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        val current = queue.firstOrNull { it.long("qid") == s.long("current_qid") }
        return old.copy(
            connection = ConnectionState.Connected("Killmon JAM"), roomCode = s.string("code"), pendingJoinCode = null,
            isHost = host == old.selfUserId, queue = queue.map(::decodeJamTrack), queueIds = queue.map { it.long("qid") }, queueContributors = queue.map { it.string("added_by") },
            queueIsVideo = queue.map { (it["is_video"] as? JsonPrimitive)?.booleanOrNull == true },
            queueAutomatic = queue.map { (it["automatic"] as? JsonPrimitive)?.booleanOrNull == true },
            currentTrack = current?.let(::decodeJamTrack), position = ((s["position"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong(),
            isPlaying = s["playing"]?.jsonPrimitive?.booleanOrNull == true, lastActionServerTime = s.long("server_time"),
            seq = s.long("seq"), loading = s.string("phase") == "loading", gateTimedOut = gate?.get("timed_out")?.jsonPrimitive?.booleanOrNull == true,
            waitingFor = waiting,
            members = s["members"]?.jsonArray.orEmpty().map { member -> val u = member.jsonObject
                RoomMember(u.string("id"), u.string("name"), u.string("id") == host, u["connected"]?.jsonPrimitive?.booleanOrNull == true, u["loading"]?.jsonPrimitive?.booleanOrNull == true)
            },
        )
}
