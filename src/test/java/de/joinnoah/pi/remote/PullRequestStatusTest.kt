package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PullRequestStatusTest {
    private fun checks(state: String = "done", failed: Int = 0) =
        JsonObject(mapOf("state" to JsonPrimitive(state), "failed" to JsonPrimitive(failed)))

    private fun pullRequest(
        number: JsonElement = JsonPrimitive(7),
        url: JsonElement = JsonPrimitive("https://github.com/acme/app/pull/7"),
        checks: JsonElement = checks(),
        failingChecks: JsonElement? = null,
    ) = JsonObject(
        buildMap {
            put("number", number)
            put("url", url)
            put("draft", JsonPrimitive(false))
            put("review", JsonNull)
            put("checks", checks)
            if (failingChecks != null) put("failingChecks", failingChecks)
        }
    )

    @Test
    fun aValidValueParsesIntoTheModel() {
        val status = pullRequestStatus(
            pullRequest(
                checks = checks("done", 2),
                failingChecks = JsonArray(listOf(JsonPrimitive("build"), JsonPrimitive("lint"))),
            )
        )
        assertEquals(
            PullRequestStatus(
                number = 7,
                url = "https://github.com/acme/app/pull/7",
                draft = false,
                review = PullRequestReview.NONE,
                checks = PullRequestChecksState.DONE,
                failedChecks = 2,
                failingChecks = listOf("build", "lint"),
            ),
            status,
        )
        assertEquals(emptyList<String>(), pullRequestStatus(pullRequest())?.failingChecks)
    }

    @Test
    fun numbersAreExactSafeIntegers() {
        assertEquals(9007199254740991L, pullRequestStatus(pullRequest(number = JsonPrimitive(9007199254740991L)))?.number)
        assertNull(pullRequestStatus(pullRequest(number = JsonPrimitive(9007199254740992L))))
        assertNull(pullRequestStatus(pullRequest(number = JsonPrimitive(0))))
        assertNull(pullRequestStatus(pullRequest(number = JsonPrimitive(1.5))))
        assertNull(pullRequestStatus(pullRequest(number = JsonPrimitive("7"))))
        assertNull(pullRequestStatus(pullRequest(number = JsonPrimitive(true))))
    }

    @Test
    fun urlAndNameLimitsCountUtf8BytesNotCharacters() {
        val prefix = "https://h.example/"
        // "é" is two bytes: 2048 bytes fit, one more does not, though both are far below 2048 chars.
        val fits = prefix + "é".repeat((MAX_PULL_REQUEST_URL_BYTES - prefix.length) / 2)
        assertEquals(MAX_PULL_REQUEST_URL_BYTES, fits.encodeToByteArray().size)
        assertNotNull(pullRequestStatus(pullRequest(url = JsonPrimitive(fits))))
        assertNull(pullRequestStatus(pullRequest(url = JsonPrimitive(fits + "a"))))
        assertNull(pullRequestStatus(pullRequest(url = JsonPrimitive(prefix + "é".repeat(MAX_PULL_REQUEST_URL_BYTES / 2)))))

        fun withName(name: String) =
            pullRequestStatus(
                pullRequest(checks = checks("done", 1), failingChecks = JsonArray(listOf(JsonPrimitive(name))))
            )
        assertNotNull(withName("é".repeat(MAX_CHECK_NAME_BYTES / 2)))
        assertNull(withName("é".repeat(MAX_CHECK_NAME_BYTES / 2) + "a"))
        assertNull(withName(""))
    }

    @Test
    fun sharedPullRequestFixturesParseAsTheHostDoes() {
        val fixture =
            Wire.json.parseToJsonElement(javaClass.getResource("/session-pr-status-v1.json")!!.readText()).jsonObject
        fun payloads(list: String) = fixture.array(list).map { it.text("name") to it.obj("payload") }
        fun valid(payload: JsonObject): Boolean =
            when (payload.text("type")) {
                "event" -> sessionPullRequest(payload) != null
                // A snapshot result: the optional key is absent or a valid pull request.
                "result" -> payload.obj("data").let { data ->
                    "pullRequest" !in data || pullRequestStatus(data["pullRequest"]) != null
                }
                else -> error(payload.text("type"))
            }
        assertEquals(8, payloads("wireValid").size)
        for ((name, payload) in payloads("wireValid")) assertTrue(name, valid(payload))
        assertEquals(47, payloads("wireInvalid").size)
        for ((name, payload) in payloads("wireInvalid")) assertFalse(name, valid(payload))

        // The valid snapshot with a pull request reaches a Timeline; the one without leaves none.
        val snapshots = payloads("wireValid").filter { it.second.text("type") == "result" }
        for ((name, payload) in snapshots) {
            val data = payload.obj("data")
            val timeline = Timeline(data.text("sessionId"))
            timeline.snapshot(data)
            assertEquals(name, "pullRequest" in data, timeline.pullRequest != null)
        }
    }
}
