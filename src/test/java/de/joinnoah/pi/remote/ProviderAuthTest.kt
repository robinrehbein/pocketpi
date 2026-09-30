package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Runs the shared `provider-auth-v1.json` fixture through the app's validators. */
class ProviderAuthTest {
    private val fixture =
        Wire.json.parseToJsonElement(javaClass.getResource("/provider-auth-v1.json")!!.readText()).jsonObject
    private val valid = fixture.getValue("valid").jsonArray.map { it.jsonObject }
    private val invalid = fixture.getValue("invalid").jsonArray.map { it.jsonObject }

    private fun payload(entry: JsonObject) = entry.obj("payload")

    private fun valid(name: String) = payload(valid.single { it.text("name") == name })

    private fun rejects(entry: JsonObject) {
        val value = payload(entry)
        try {
            when (value.text("type")) {
                "result" -> parseResult(value.obj("data"))
                "host.event" -> providerAuthEvent(value) ?: throw IllegalArgumentException("not a provider event")
                else -> return // A command: the app builds these and never parses them.
            }
        } catch (_: IllegalArgumentException) {
            return
        } catch (_: NoSuchElementException) {
            return
        } catch (_: IllegalStateException) {
            return
        } catch (_: NumberFormatException) {
            return
        }
        fail("accepted invalid fixture entry ${entry.text("name")}")
    }

    private fun parseResult(data: JsonObject): Any =
        when (data.text("kind")) {
            "provider.auth.list" -> parseProviderList(data)
            "provider.auth.login" -> parseLoginStarted(data)
            "provider.auth.login.state" -> parseLoginStatus(data)
            "provider.auth.logout" -> parseLogout(data, data.text("providerId"))
            "accepted" -> requireAccepted(data)
            else -> throw IllegalArgumentException("Unknown result")
        }

    @Test
    fun everyValidResultAndEventParses() {
        var results = 0
        var events = 0
        valid.forEach { entry ->
            val value = payload(entry)
            when (value.text("type")) {
                "result" ->
                    if (value.flag("ok")) {
                        val data = value.obj("data")
                        if (data.text("kind").let { it.startsWith("provider.auth.") || it == "accepted" }) {
                            assertNotNull(entry.text("name"), parseResult(data))
                            results++
                        }
                    }
                "host.event" -> {
                    assertNotNull(entry.text("name"), providerAuthEvent(value))
                    events++
                }
            }
        }
        assertTrue(results >= 8)
        assertTrue(events >= 8)
    }

    @Test
    fun everyInvalidResultAndEventIsRejected() = invalid.forEach(::rejects)

    @Test
    fun listResultKeepsStatusesAndActiveLogin() {
        val list = parseProviderList(valid("list-result").obj("data"))
        assertEquals(listOf("anthropic", "openrouter", "amazon-bedrock"), list.providers.map { it.id })
        val anthropic = list.providers.first()
        assertEquals(listOf(ProviderAuthMethod.OAUTH, ProviderAuthMethod.API_KEY), anthropic.methods)
        assertEquals(ProviderStatus(true, ProviderKeySource.STORED, ProviderAuthMethod.OAUTH), anthropic.status)
        assertTrue(anthropic.storedLogin)
        assertFalse(list.providers[1].status.configured)
        val bedrock = list.providers[2]
        assertTrue(bedrock.methods.isEmpty())
        assertEquals(ProviderKeySource.CONFIG, bedrock.status.source)
        // A key in models.json is reported but cannot be replaced or removed from the phone.
        assertFalse(bedrock.storedLogin)

        val active = parseProviderList(valid("list-result-active-login").obj("data"))
        assertTrue(active.truncated)
        assertEquals(ActiveLogin("TG9naW5JZC0wMTIzNDU2IQ", "anthropic", LoginState.AWAITING_INPUT, true), active.login)
    }

    @Test
    fun loginStatesCarryPromptEventAndError() {
        val awaiting = parseLoginStatus(valid("state-awaiting-input").obj("data"))
        assertEquals(PromptType.MANUAL_CODE, awaiting.prompt?.prompt?.type)
        assertTrue(awaiting.lastEvent is AuthEvent.AuthUrl)
        val running = parseLoginStatus(valid("state-running-device-code").obj("data"))
        assertEquals(
            AuthEvent.DeviceCode("ABCD-1234", "https://github.com/login/device", 5, 900),
            running.lastEvent,
        )
        assertEquals(LoginError.INVALID_CODE, parseLoginStatus(valid("state-failed").obj("data")).error)
        val succeeded = parseLoginStatus(valid("state-succeeded").obj("data"))
        assertEquals(LoginState.SUCCEEDED, succeeded.state)
    }

    @Test
    fun capabilityFixtureAdvertisesProviderAuth() {
        val data = valid("projects-provider-auth-capability").obj("data")
        val advertised = data.getValue("capabilities").jsonArray.map { (it as JsonPrimitive).content }
        assertTrue(PROVIDER_AUTH_CAPABILITY in advertised)
        assertEquals("provider.auth.v1", PROVIDER_AUTH_CAPABILITY)
        assertTrue(advertised.size <= 16)
    }

    @Test
    fun errorFixturesMapToMessages() {
        listOf("error-login-in-progress" to R.string.providers_error_in_progress, "error-rate-limited" to R.string.providers_error_rate_limited)
            .forEach { (name, message) ->
                val code = valid(name).obj("error").text("code")
                assertEquals(message, providerErrorMessage(RemoteRequestException(code)))
            }
        listOf("exists", "unsupported", "not_found", "already_resolved", "forbidden", "invalid_request", "offline", "internal")
            .forEach { assertNotEquals(R.string.remote_request_error, providerErrorMessage(RemoteRequestException(it))) }
        assertEquals(R.string.remote_request_error, providerErrorMessage(IllegalStateException("Request timed out")))
        LoginState.entries.filter { it.finished }.forEach { state ->
            (LoginError.entries + null).forEach { assertNotEquals(0, loginFailureMessage(state, it)) }
        }
    }

    @Test
    fun onlyHttpsUrlsWithoutUserinfoAreOpenable() {
        assertTrue(validHttpsUrl("https://claude.ai/oauth/authorize?client_id=example"))
        assertTrue(validHttpsUrl("https://example.com:8443/path#x"))
        assertFalse(validHttpsUrl("http://claude.ai/oauth"))
        assertFalse(validHttpsUrl("javascript:alert(1)"))
        assertFalse(validHttpsUrl("https://user:pw@claude.ai/"))
        assertFalse(validHttpsUrl("https://evil.example\\@good.example/"))
        assertFalse(validHttpsUrl("https:///path"))
        assertFalse(validHttpsUrl("https://claude.ai/a b"))
        assertFalse(validHttpsUrl("https://claude.ai/" + "a".repeat(2048)))
        assertTrue(validProviderId("github-copilot"))
        assertFalse(validProviderId("a b"))
        assertFalse(validProviderId(""))
    }

    private val loginId = "TG9naW5JZC0wMTIzNDU2IQ"
    private val promptId = "UHJvbXB0SWQtMDEyMzQ1Ng"

    private fun update(name: String) = checkNotNull(providerAuthEvent(valid(name)))

    @Test
    fun reducerFollowsPromptAnswerAndFinish() {
        var flow = LoginFlow(loginId, "anthropic", ProviderAuthMethod.OAUTH)
        flow = flow.updatedBy(update("event-auth-url"), 1_000)
        assertNotNull(flow.authUrl)
        assertTrue(flow.needsSecureWindow())
        flow = flow.updatedBy(update("prompt-secret"), 2_000)
        assertEquals(LoginState.AWAITING_INPUT, flow.state)
        assertEquals(PromptType.SECRET, flow.prompt?.prompt?.type)
        val pending = checkNotNull(flow.prompt)
        // The prompt of an older login or another ID does not touch a newer one.
        assertEquals(flow, flow.updatedBy(ProviderAuthUpdate.PromptClosed(loginId, "AAAAAAAAAAAAAAAAAAAAAA", PromptCloseReason.ANSWERED), 0))
        flow = flow.copy(answering = true).answered(pending.promptId)
        assertNull(flow.prompt)
        assertEquals(LoginState.RUNNING, flow.state)
        assertFalse(flow.answering)
        flow = flow.updatedBy(update("finished-succeeded"), 3_000)
        assertEquals(LoginState.SUCCEEDED, flow.state)
        assertFalse(flow.modelStale)
        assertFalse(flow.needsSecureWindow())
        // A finished flow no longer changes, not even by a late prompt.
        assertEquals(flow, flow.updatedBy(update("prompt-secret"), 4_000))
    }

    @Test
    fun aClosedPromptReturnsToRunning() {
        val flow = LoginFlow(loginId, "anthropic", null).updatedBy(update("prompt-text"), 0)
        assertEquals(LoginState.AWAITING_INPUT, flow.state)
        val closed = flow.updatedBy(update("prompt-closed"), 0)
        assertNull(closed.prompt)
        assertEquals(LoginState.RUNNING, closed.state)
    }

    @Test
    fun failureAndTimeoutKeepTheHostsReason() {
        val failed = LoginFlow(loginId, "anthropic", null).updatedBy(update("finished-failed"), 0)
        assertEquals(LoginState.FAILED, failed.state)
        assertNotNull(failed.error)
        val timeout = LoginFlow(loginId, "anthropic", null).updatedBy(update("finished-timeout"), 0)
        assertEquals(LoginState.TIMEOUT, timeout.state)
        assertEquals(LoginError.TIMEOUT, timeout.error)
    }

    @Test
    fun recoveredStateHasNoDeviceCodeCountdown() {
        val status = parseLoginStatus(valid("state-running-device-code").obj("data"))
        val flow = LoginFlow(loginId, "github-copilot", null).updatedBy(status)
        assertNotNull(flow.deviceCode)
        assertNull(flow.deviceCodeAt)
        val live = LoginFlow(loginId, "github-copilot", null).updatedBy(update("event-device-code"), 5_000)
        assertEquals(5_000L, live.deviceCodeAt)
        assertEquals("1:05", formatCountdown(65))
        assertEquals("0:00", formatCountdown(0))
    }

    @Test
    fun recoveryFromAwaitingStateRestoresManualCodePrompt() {
        val status = parseLoginStatus(valid("state-awaiting-input").obj("data"))
        val flow = LoginFlow(loginId, "anthropic", null).updatedBy(status)
        assertEquals(promptId, flow.prompt?.promptId)
        assertNotNull(flow.authUrl)
        assertTrue(flow.needsSecureWindow())
    }

    @Test
    fun listAdoptsOwnLoginAndShowsForeignOne() {
        val own = ProviderAuthState(routeId = "host").withList(parseProviderList(valid("list-result-active-login").obj("data")))
        assertEquals(loginId, own.flow?.loginId)
        assertNull(own.other)
        assertTrue(own.busy)
        val foreignData = valid("list-result-active-login").obj("data")
        val foreign =
            JsonObject(
                foreignData + ("login" to JsonObject(foreignData.obj("login") + ("own" to JsonPrimitive(false))))
            )
        val other = ProviderAuthState(routeId = "host").withList(parseProviderList(foreign))
        assertNull(other.flow)
        assertEquals("anthropic", other.other?.providerId)
        assertTrue(other.busy)
        // A list without any login clears the foreign banner.
        val idle = other.withList(parseProviderList(valid("list-result").obj("data")))
        assertNull(idle.other)
        assertFalse(idle.busy)
    }

    @Test
    fun secureWindowOnlyForSecretsCodesAndLinks() {
        val base = LoginFlow(loginId, "anthropic", null)
        assertFalse(base.needsSecureWindow())
        assertTrue(base.updatedBy(update("prompt-secret"), 0).needsSecureWindow())
        assertFalse(base.updatedBy(update("prompt-select"), 0).needsSecureWindow())
        assertTrue(base.updatedBy(update("event-device-code"), 0).needsSecureWindow())
        assertFalse(base.updatedBy(update("event-progress"), 0).needsSecureWindow())
    }

    @Test
    fun unknownProviderAuthEventKindThrowsAndOtherKindsPassThrough() {
        assertNull(providerAuthEvent(JsonObject(mapOf("type" to JsonPrimitive("host.event"), "kind" to JsonPrimitive("clone.progress")))))
        try {
            providerAuthEvent(JsonObject(mapOf("type" to JsonPrimitive("host.event"), "kind" to JsonPrimitive("provider.auth.future"))))
            fail("unknown provider.auth kind accepted")
        } catch (_: IllegalArgumentException) {
        }
    }

}
