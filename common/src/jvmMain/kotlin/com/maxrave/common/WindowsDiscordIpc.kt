package com.maxrave.common

import com.maxrave.logger.Logger
import java.io.EOFException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Discord Rich Presence over the local IPC pipe (`\\.\pipe\discord-ipc-0` … `9`).
 *
 * This is the same channel the Discord desktop app exposes to games. It does not use a user
 * token and does not open a gateway websocket. A frame is a little-endian opcode, a little-endian
 * length, then UTF-8 JSON.
 */
class WindowsDiscordIpcSession(
    private val applicationId: String,
) {
    private val lock = Any()
    private var pipe: RandomAccessFile? = null

    @Volatile
    private var active: Boolean = false

    fun isActive(): Boolean = active && pipe != null

    fun connect(): Boolean = synchronized(lock) { connectLocked() }

    fun close() = synchronized(lock) { dropPipe() }

    /**
     * [stillCurrent] is read under the pipe lock. Pause can win the lock first and drop the pipe;
     * an update that already passed the caller's "is playing" check then sees `false` and does not
     * open it again.
     */
    fun setActivity(
        activity: DiscordIpcActivity,
        stillCurrent: () -> Boolean = { true },
    ): Boolean =
        synchronized(lock) {
            if (!stillCurrent()) return false
            if (!connectLocked()) return false
            val pid = currentPid()
            val first = sendActivity(activity, pid)
            if (first == IpcSend.OK) {
                if (!stillCurrent()) dropPipe()
                return true
            }
            if (first == IpcSend.CLOSED) return false
            if (activity.largeImage.isNullOrBlank() && activity.smallImage.isNullOrBlank()) {
                return false
            }
            // Some Discord builds reject https URLs in assets and fail the whole activity.
            Logger.w(TAG, "Discord IPC rejected artwork, retrying with text only")
            val textOnly =
                activity.copy(
                    largeImage = null,
                    smallImage = null,
                )
            if (!stillCurrent()) {
                dropPipe()
                return false
            }
            return sendActivity(textOnly, pid) == IpcSend.OK
        }

    private fun sendActivity(
        activity: DiscordIpcActivity,
        pid: Long,
    ): IpcSend {
        val nonce = UUID.randomUUID().toString()
        return try {
            writeFrameLocked(OP_FRAME, discordIpcSetActivityJson(activity, pid, nonce, applicationId))
            val frame = readMatchingLocked(nonce, READ_TIMEOUT_MS)
            if (frame == null) {
                dropPipe()
                IpcSend.CLOSED
            } else if (isError(frame.json)) {
                Logger.w(TAG, "Discord IPC SET_ACTIVITY error: ${frame.json.take(LOG_JSON)}")
                IpcSend.REJECTED
            } else {
                IpcSend.OK
            }
        } catch (e: IOException) {
            Logger.w(TAG, "Discord IPC write failed: ${e.message}")
            dropPipe()
            IpcSend.CLOSED
        }
    }

    private fun connectLocked(): Boolean {
        if (!isWindows()) return false
        if (active && pipe != null) return true
        dropPipe()
        for (index in 0..LAST_PIPE_INDEX) {
            val opened = openPipe(index) ?: continue
            pipe = opened
            try {
                writeFrameLocked(OP_HANDSHAKE, discordIpcHandshakeJson(applicationId))
                val frame = readMatchingLocked(nonce = null, timeoutMs = HANDSHAKE_TIMEOUT_MS)
                if (frame != null && isReady(frame.json)) {
                    active = true
                    Logger.i(TAG, "Connected to Discord IPC pipe $index")
                    return true
                }
                Logger.w(TAG, "Discord IPC pipe $index handshake was not READY")
            } catch (e: IOException) {
                Logger.w(TAG, "Discord IPC pipe $index failed: ${e.message}")
            }
            dropPipe()
        }
        return false
    }

    private fun openPipe(index: Int): RandomAccessFile? {
        val paths =
            listOf(
                "\\\\.\\pipe\\discord-ipc-$index",
                "\\\\?\\pipe\\discord-ipc-$index",
            )
        for (path in paths) {
            val opened = runCatching { RandomAccessFile(path, "rw") }.getOrNull()
            if (opened != null) return opened
        }
        return null
    }

    /** Drops the current pipe without retiring the session, so the next update can reconnect. */
    private fun dropPipe() {
        active = false
        val current = pipe ?: return
        pipe = null
        runCatching { writeFrame(current, OP_CLOSE, "{}") }
        runCatching { current.close() }
    }

    private fun writeFrameLocked(
        opcode: Int,
        json: String,
    ) {
        val current = pipe ?: throw IOException("Discord IPC is closed")
        writeFrame(current, opcode, json)
    }

    private fun readMatchingLocked(
        nonce: String?,
        timeoutMs: Long,
    ): IpcFrame? {
        val deadline = System.currentTimeMillis() + timeoutMs
        repeat(MAX_UNRELATED_FRAMES) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0L) return null
            val frame = readFrameLocked(left) ?: return null
            if (nonce == null || frame.json.contains(nonce)) return frame
        }
        return null
    }

    /**
     * Reads one frame on a daemon thread so a Discord client that never answers cannot pin the
     * caller. On timeout the pipe is closed; a half-read frame cannot be resynced.
     */
    private fun readFrameLocked(timeoutMs: Long): IpcFrame? {
        val current = pipe ?: return null
        val frame = AtomicReference<IpcFrame?>(null)
        val failure = AtomicReference<Exception?>(null)
        val reader =
            Thread {
                try {
                    frame.set(readFrameBlocking(current))
                } catch (e: Exception) {
                    failure.set(e)
                }
            }
        reader.isDaemon = true
        reader.name = "killmon-discord-ipc"
        reader.start()
        reader.join(timeoutMs.coerceAtLeast(1L))
        if (reader.isAlive) {
            Logger.w(TAG, "Discord IPC read timed out")
            dropPipe()
            reader.join(JOIN_AFTER_CLOSE_MS)
            return null
        }
        val error = failure.get()
        if (error != null) {
            Logger.w(TAG, "Discord IPC read failed: ${error.message}")
            dropPipe()
            return null
        }
        return frame.get()
    }

    private enum class IpcSend { OK, REJECTED, CLOSED }

    companion object {
        private const val TAG = "DiscordIpc"
        private const val OP_HANDSHAKE = 0
        private const val OP_FRAME = 1
        private const val OP_CLOSE = 2
        private const val LAST_PIPE_INDEX = 9
        private const val HANDSHAKE_TIMEOUT_MS = 2_500L
        private const val READ_TIMEOUT_MS = 4_000L
        private const val JOIN_AFTER_CLOSE_MS = 500L
        private const val MAX_UNRELATED_FRAMES = 4
        private const val MAX_FRAME_BYTES = 1_048_576
        private const val LOG_JSON = 400
        private const val PIPE_RETRY_MS = 30L
        private const val PIPE_RETRY_BUDGET_MS = 2_000L

        private fun isReady(json: String): Boolean = json.replace(" ", "").contains("\"evt\":\"READY\"")

        private fun isError(json: String): Boolean = json.replace(" ", "").contains("\"evt\":\"ERROR\"")

        fun isWindows(): Boolean =
            System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)

        /** Opens the pipe, checks the READY handshake, then closes. Does not set an activity. */
        fun probe(applicationId: String): Boolean {
            if (!isWindows()) return false
            val session = WindowsDiscordIpcSession(applicationId)
            return try {
                session.connect()
            } finally {
                session.close()
            }
        }

        private fun currentPid(): Long =
            runCatching { ProcessHandle.current().pid() }.getOrDefault(0L)

        private fun writeFrame(
            pipe: RandomAccessFile,
            opcode: Int,
            json: String,
        ) {
            val data = json.toByteArray(StandardCharsets.UTF_8)
            val header =
                ByteBuffer
                    .allocate(8)
                    .order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(opcode)
            header.putInt(data.size)
            pipe.write(header.array())
            pipe.write(data)
        }

        private fun readFrameBlocking(pipe: RandomAccessFile): IpcFrame {
            val opcode = readIntLe(pipe)
            val length = readIntLe(pipe)
            if (length !in 0..MAX_FRAME_BYTES) throw IOException("Discord IPC frame length $length")
            val payload = ByteArray(length)
            pipe.readFully(payload)
            return IpcFrame(opcode, payload.toString(StandardCharsets.UTF_8))
        }

        private fun readIntLe(pipe: RandomAccessFile): Int {
            val b0 = readByte(pipe)
            val b1 = readByte(pipe)
            val b2 = readByte(pipe)
            val b3 = readByte(pipe)
            return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }

        /**
         * Windows named pipes often throw "Incorrect function" from [RandomAccessFile.read] until
         * Discord has written the handshake reply. Retry those, and fail on a real EOF.
         */
        private fun readByte(pipe: RandomAccessFile): Int {
            val deadline = System.currentTimeMillis() + PIPE_RETRY_BUDGET_MS
            while (true) {
                try {
                    val value = pipe.read()
                    if (value < 0) throw EOFException("Discord IPC closed")
                    return value
                } catch (e: IOException) {
                    val message = e.message.orEmpty()
                    val retryable = message.contains("incorrect", ignoreCase = true)
                    if (!retryable || System.currentTimeMillis() > deadline) throw e
                    Thread.sleep(PIPE_RETRY_MS)
                }
            }
        }
    }
}

private data class IpcFrame(
    val opcode: Int,
    val json: String,
)
