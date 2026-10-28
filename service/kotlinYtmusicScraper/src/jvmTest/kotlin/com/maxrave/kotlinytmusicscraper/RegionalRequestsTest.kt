package com.maxrave.kotlinytmusicscraper

import com.maxrave.kotlinytmusicscraper.models.YouTubeClient.Companion.WEB_REMIX
import com.maxrave.kotlinytmusicscraper.models.YouTubeLocale
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class RegionalRequestsTest {
    private fun client(requests: MutableList<HttpRequestData>): Ytmusic =
        Ytmusic(object : HttpClientEngineFactory<HttpClientEngineConfig> {
            override fun create(block: HttpClientEngineConfig.() -> Unit) =
                MockEngine { request ->
                    requests += request
                    respond("{}", HttpStatusCode.OK)
                }
        })

    private fun assertContext(request: HttpRequestData, country: String, language: String) {
        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
        val context = body.getValue("context").jsonObject.getValue("client").jsonObject
        assertEquals(country, context.getValue("gl").jsonPrimitive.content)
        assertEquals(language, context.getValue("hl").jsonPrimitive.content)
    }

    @Test fun homeChartsNewReleasesAndMusicSuggestionsUseConfiguredLocale() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val client = client(requests)
        client.locale = YouTubeLocale("IN", "hi") // stale initial value
        client.localeProvider = { YouTubeLocale("CO", "es-419") }
        for (id in listOf("FEmusic_home", "FEmusic_charts", "FEmusic_new_releases")) {
            client.browse(WEB_REMIX, id, countryCode = if (id == "FEmusic_charts") "CO" else null)
        }
        client.getSearchSuggestions(WEB_REMIX, "musica")
        requests.forEach { assertContext(it, "CO", "es-419") }
        val chart = Json.parseToJsonElement((requests[1].body as TextContent).text).jsonObject
        assertEquals("CO", chart.getValue("formData").jsonObject.getValue("selectedValues").jsonArray[0].jsonPrimitive.content)
    }

    @Test fun googleSuggestionsAndVisitorBootstrapSendRegionAndLanguage() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val client = client(requests)
        client.getSuggestQuery("musica")
        client.getSwJsData()
        requests.forEach {
            assertEquals("CO", it.url.parameters["gl"])
            assertEquals("es-419", it.url.parameters["hl"])
        }
    }

    @Test fun firstRequestAfterRegionChangeUsesNewSelection() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val client = client(requests)
        var selected = YouTubeLocale("CO", "es-419")
        client.localeProvider = { selected }
        client.browse(WEB_REMIX, "FEmusic_home")
        selected = YouTubeLocale("MX", "es-419")
        client.browse(WEB_REMIX, "FEmusic_home")
        assertContext(requests[0], "CO", "es-419")
        assertContext(requests[1], "MX", "es-419")
    }
}
