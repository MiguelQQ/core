package org.simpmusic.listentogether

import kotlin.test.*
import kotlinx.serialization.json.Json

class LiveRoomSyncTest {
    @Test fun driftThreshold() {
        assertFalse(JamSync.needsSeek(1000, 1500))
        assertTrue(JamSync.needsSeek(1000, 1501))
        assertTrue(JamSync.needsSeek(1501, 1000))
    }
    @Test fun readyRequiresStateAndThreeSeconds() {
        assertFalse(JamSync.ready(2, 10000, 0, 20000))
        assertFalse(JamSync.ready(3, 2999, 0, 20000))
        assertTrue(JamSync.ready(3, 3000, 0, 20000))
        assertTrue(JamSync.ready(3, 2000, 0, 2000))
    }
    @Test fun queueCodecUsesMillisecondsAndServerFields() {
        val track = TrackInfo(id = "dQw4w9WgXcQ", title = "Título", artist = "Artista", duration = 123000)
        val encoded = encodeJamTrack(track)
        assertEquals(123000, encoded.long("durationMs"))
        val snapshot = Json.parseToJsonElement("""{"videoId":"dQw4w9WgXcQ","title":"Título","artist":"Artista","duration_ms":123000,"unknown":true}""").let { it as kotlinx.serialization.json.JsonObject }
        assertEquals(track, decodeJamTrack(snapshot))
    }
    @Test fun gateAndHostTransferDecode() {
        val raw = """{"code":"ABC234","seq":5,"phase":"loading","host_id":"a","playing":false,"position":0,"server_time":1000,"current_qid":7,"queue":[{"qid":7,"videoId":"dQw4w9WgXcQ","title":"A"}],"members":[{"id":"b","name":"B","connected":true,"loading":true}],"gate":{"waiting":["b"],"timed_out":true}}"""
        val json = Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject
        val loading = decodeJamState(json, ListenTogetherState(selfUserId = "b"))
        assertTrue(loading.loading); assertFalse(loading.isHost); assertTrue(loading.gateTimedOut)
        assertEquals(listOf("b"), loading.waitingFor); assertTrue(loading.members.single().isBuffering)
        assertEquals(5L, loading.seq); assertEquals(listOf(7L), loading.queueIds)
        val transferred = decodeJamState(Json.parseToJsonElement(raw.replace("\"host_id\":\"a\"", "\"host_id\":\"b\"")) as kotlinx.serialization.json.JsonObject, loading)
        assertTrue(transferred.isHost)
        val idle = decodeJamState(Json.parseToJsonElement("""{"code":"ABC234","phase":"idle","queue":[],"members":[]}""") as kotlinx.serialization.json.JsonObject, loading)
        assertNull(idle.currentTrack); assertTrue(idle.queue.isEmpty()); assertTrue(idle.waitingFor.isEmpty())
    }
}
