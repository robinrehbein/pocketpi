package de.joinnoah.pi.remote

import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProviderAuthRepositoryTest {
    private val host = PairedHost("host", "https://relay.test", "device", "secret", "Studio Mac")
    private val fixture =
        Wire.json.parseToJsonElement(javaClass.getResource("/provider-auth-v1.json")!!.readText()).jsonObject
    private val loginId = "TG9naW5JZC0wMTIzNDU2IQ"
    private val promptId = "UHJvbXB0SWQtMDEyMzQ1Ng"
    private val types =
        setOf(
            "provider.auth.list",
            "provider.auth.login.start",
            "provider.auth.login.answer",
            "provider.auth.login.cancel",
            "provider.auth.login.status",
            "provider.auth.logout",
        )

    private fun valid(name: String) =
        fixture.getValue("valid").jsonArray.map { it.jsonObject }.single { it.text("name") == name }.obj("payload")

    private fun data(name: String) = valid(name).obj("data")

    private class Pairings(var hosts: List<PairedHost>) : PairingStorage {
        override fun load() = hosts

        override fun save(hosts: List<PairedHost>) {
            this.hosts = hosts
        }
    }

    private class Drafts : DraftStorage {
        override fun load() = emptyMap<DraftKey, StoredDraft>()

        override fun save(drafts: Map<DraftKey, StoredDraft>) {}
    }

    private class Transport : RemoteTransport {
        override lateinit var listener: RemoteTransport.Listener
        val sent = mutableListOf<JsonObject>()
        var advertised = listOf(PROVIDER_AUTH_CAPABILITY)
        val failures = mutableMapOf<String, JsonObject>()
        val replies = mutableMapOf<String, (JsonObject) -> JsonObject?>()

        override fun connect(host: PairedHost) {
            listener.ready(emptySet())
        }

        override fun pair(value: JsonObject) {}

        override fun close() {}

        override fun send(payload: JsonObject) {
            sent += payload
            val type = payload.text("type")
            fun reply(ok: Boolean, key: String, body: JsonObject) =
                listener.message(
                    Wire.objectOf("type" to "result", "requestId" to payload.text("requestId"), "ok" to ok, key to body)
                )
            failures.remove(type)?.let { return reply(false, "error", it) }
            val data =
                replies[type]?.invoke(payload)
                    ?: when (type) {
                        "projects.list" ->
                            Wire.objectOf(
                                "kind" to "projects",
                                "items" to JsonArray(listOf(Wire.objectOf("id" to "project", "name" to "project"))),
                                "capabilities" to JsonArray(advertised.map(::JsonPrimitive)),
                            )
                        "sessions.list" -> Wire.objectOf("kind" to "sessions", "items" to JsonArray(emptyList()))
                        else -> null
                    }
                    ?: return
            reply(true, "data", data)
        }

        fun event(value: JsonObject) = listener.message(value)

        fun of(type: String) = sent.filter { it.text("type") == type }
    }

    private fun TestScope.repository(transport: Transport) =
        DefaultRemoteRepository(
            Pairings(listOf(host)),
            Drafts(),
            transport,
            backgroundScope,
            StandardTestDispatcher(testScheduler),
            now = { testScheduler.currentTime },
        )

    private suspend fun TestScope.connected(transport: Transport): DefaultRemoteRepository {
        transport.replies["provider.auth.list"] = { data("list-result") }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host"))
        runCurrent()
        return repository
    }


    private fun DefaultRemoteRepository.providers() = state.value.providerAuth

    @Test
    fun withoutCapabilityNoProviderCommandIsSent() = runTest {
        val transport = Transport().apply { advertised = emptyList() }
        val repository = connected(transport)
        repository.browseProviders("host")
        repository.startLogin("anthropic", ProviderAuthMethod.OAUTH, false)
        repository.answerLogin(promptId, "sk-x")
        repository.cancelLogin()
        repository.logoutProvider("anthropic")
        runCurrent()
        assertTrue(transport.sent.none { it.text("type") in types })
        assertFalse(canManageProviders(repository.state.value))
    }

    @Test
    fun capabilityLoadsTheProviderList() = runTest {
        val transport = Transport()
        val repository = connected(transport)
        assertTrue(canManageProviders(repository.state.value))
        repository.browseProviders("host")
        runCurrent()
        assertEquals(1, transport.of("provider.auth.list").size)
        assertEquals(setOf("type", "requestId"), transport.of("provider.auth.list").single().keys)
        val state = repository.providers()
        assertTrue(state.loaded)
        assertEquals(listOf("anthropic", "openrouter", "amazon-bedrock"), state.providers.map { it.id })
    }

    @Test
    fun startSendsTheFixtureCommandAndTracksTheFlow() = runTest {
        val transport = Transport()
        val repository = connected(transport)
        repository.browseProviders("host")
        runCurrent()
        // The host's first event may reach the phone before the start result is handled.
        transport.replies["provider.auth.login.start"] = {
            transport.event(valid("event-auth-url"))
            data("login-result")
        }
        repository.startLogin("anthropic", ProviderAuthMethod.OAUTH, false)
        runCurrent()
        val command = transport.of("provider.auth.login.start").single()
        assertEquals(valid("start-command-oauth") - "requestId", command - "requestId")
        var flow = checkNotNull(repository.providers().flow)
        assertEquals(loginId, flow.loginId)
        assertNotNull(flow.authUrl)
        assertFalse(repository.providers().working)
        // A second start while the login runs is not sent.
        repository.startLogin("openrouter", ProviderAuthMethod.API_KEY, false)
        runCurrent()
        assertEquals(1, transport.of("provider.auth.login.start").size)

        transport.event(valid("prompt-secret"))
        flow = checkNotNull(repository.providers().flow)
        assertEquals(LoginState.AWAITING_INPUT, flow.state)
        transport.event(valid("finished-succeeded"))
        runCurrent()
        assertEquals(LoginState.SUCCEEDED, repository.providers().flow?.state)
        // The outcome refreshes the list.
        assertEquals(2, transport.of("provider.auth.list").size)
        repository.dismissLogin()
        assertNull(repository.providers().flow)
    }

    @Test
    fun replaceIsSentOnlyWhenConfirmed() = runTest {
        val transport = Transport()
        val repository = connected(transport)
        repository.browseProviders("host")
        runCurrent()
        transport.replies["provider.auth.login.start"] = { data("login-result") }
        repository.startLogin("anthropic", ProviderAuthMethod.OAUTH, true)
        runCurrent()
        assertEquals(true, transport.of("provider.auth.login.start").single().flag("replace"))
    }

    @Test
    fun answerSendsTheValueOnceAndKeepsItOutOfState() = runTest {
        val transport = Transport()
        val repository = connected(transport)
        repository.browseProviders("host")
        runCurrent()
        transport.replies["provider.auth.login.start"] = { data("login-result") }
        repository.startLogin("anthropic", ProviderAuthMethod.OAUTH, false)
        runCurrent()
        transport.event(valid("prompt-secret"))
        transport.replies["provider.auth.login.answer"] = { Wire.objectOf("kind" to "accepted") }
        val secret = "sk-test-SECRET-marker"
        repository.answerLogin(promptId, secret)
        // A double tap does not send a second answer.
        repository.answerLogin(promptId, secret)
        runCurrent()
        val answer = transport.of("provider.auth.login.answer").single()
        assertEquals(secret, answer.text("value"))
        assertEquals(loginId, answer.text("loginId"))
        assertEquals(promptId, answer.text("promptId"))
        val flow = checkNotNull(repository.providers().flow)
        assertNull(flow.prompt)
        assertFalse(flow.answering)
        assertFalse(repository.state.value.toString().contains(secret))
        // A wrong prompt or oversized value is never sent.
        transport.event(valid("prompt-secret"))
        repository.answerLogin("AAAAAAAAAAAAAAAAAAAAAA", "x")
        repository.answerLogin(promptId, "x".repeat(MAX_PROVIDER_AUTH_ANSWER_BYTES + 1))
        runCurrent()
        assertEquals(1, transport.of("provider.auth.login.answer").size)
    }

    @Test
    fun aRejectedAnswerKeepsThePromptAndExplains() = runTest {
        val transport = Transport()
        val repository = connected(transport)
        repository.browseProviders("host")
        runCurrent()
        transport.replies["provider.auth.login.start"] = { data("login-result") }
        repository.startLogin("anthropic", ProviderAuthMethod.OAUTH, false)
        runCurrent()
        transport.event(valid("prompt-secret"))
        transport.failures["provider.auth.login.answer"] = Wire.objectOf("code" to "invalid_request", "message" to "no")
        repository.answerLogin(promptId, "sk-x")
        runCurrent()
        val flow = checkNotNull(repository.providers().flow)
        assertNotNull(flow.prompt)
        assertFalse(flow.answering)
        assertEquals(R.string.providers_error_invalid, flow.notice?.message)
    }

    @Test
    fun cancelWaitsForTheHostsOutcome() = runTest {
        val transport = Transport()
        val repository = connected(transport)
        repository.browseProviders("host")
        runCurrent()
        transport.replies["provider.auth.login.start"] = { data("login-result") }
        repository.startLogin("anthropic", ProviderAuthMethod.OAUTH, false)
        runCurrent()
        transport.replies["provider.auth.login.cancel"] = { Wire.objectOf("kind" to "accepted") }
        repository.cancelLogin()
        runCurrent()
        assertEquals(valid("cancel-command") - "requestId", transport.of("provider.auth.login.cancel").single() - "requestId")
        val flow = checkNotNull(repository.providers().flow)
        assertTrue(flow.cancelling)
        assertEquals(LoginState.RUNNING, flow.state)
        // The host may still report a success for a login that was cancelled late.
        transport.event(valid("finished-succeeded"))
        assertEquals(LoginState.SUCCEEDED, repository.providers().flow?.state)
        assertFalse(repository.providers().flow?.cancelling ?: true)
    }

    @Test
    fun startErrorsShowAMessageAndReloadTheList() = runTest {
        val transport = Transport()
        val repository = connected(transport)
        repository.browseProviders("host")
        runCurrent()
        transport.failures["provider.auth.login.start"] = valid("error-login-in-progress").obj("error")
        repository.startLogin("anthropic", ProviderAuthMethod.OAUTH, false)
        runCurrent()
        assertEquals(R.string.providers_error_in_progress, repository.providers().notice?.message)
        assertFalse(repository.providers().working)
        assertNull(repository.providers().flow)
        assertEquals(2, transport.of("provider.auth.list").size)
        transport.failures["provider.auth.login.start"] = valid("error-rate-limited").obj("error")
        repository.startLogin("anthropic", ProviderAuthMethod.OAUTH, false)
        runCurrent()
        assertEquals(R.string.providers_error_rate_limited, repository.providers().notice?.message)
    }

    @Test
    fun logoutSendsTheFixtureCommandAndReloads() = runTest {
        val transport = Transport()
        val repository = connected(transport)
        repository.browseProviders("host")
        runCurrent()
        transport.replies["provider.auth.logout"] = { data("logout-result") }
        repository.logoutProvider("anthropic")
        runCurrent()
        assertEquals(valid("logout-command") - "requestId", transport.of("provider.auth.logout").single() - "requestId")
        assertEquals(R.string.providers_logout_done, repository.providers().notice?.message)
        assertEquals("Anthropic", repository.providers().notice?.provider)
        assertEquals(2, transport.of("provider.auth.list").size)
    }

    @Test
    fun ownRunningLoginIsRecoveredFromListAndStatus() = runTest {
        val transport = Transport()
        val repository = connected(transport)
        transport.replies["provider.auth.list"] = { data("list-result-active-login") }
        transport.replies["provider.auth.login.status"] = { data("state-awaiting-input") }
        // A fresh app process knows no flow; the list names it and status restores the prompt.
        repository.browseProviders("host")
        runCurrent()
        val status = transport.of("provider.auth.login.status").single()
        assertEquals(valid("status-command") - "requestId", status - "requestId")
        val flow = checkNotNull(repository.providers().flow)
        assertEquals(PromptType.MANUAL_CODE, flow.prompt?.prompt?.type)
        assertNotNull(flow.authUrl)
    }

    @Test
    fun aLoginThatFinishedWhileAwayShowsItsOutcome() = runTest {
        val transport = Transport()
        val repository = connected(transport)
        transport.replies["provider.auth.list"] = { data("list-result-active-login") }
        transport.replies["provider.auth.login.status"] = { data("state-failed") }
        repository.browseProviders("host")
        runCurrent()
        val flow = checkNotNull(repository.providers().flow)
        assertEquals(LoginState.FAILED, flow.state)
        assertEquals(LoginError.INVALID_CODE, flow.error)
    }

    @Test
    fun anExpiredLoginIsDroppedWithANotice() = runTest {
        val transport = Transport()
        val repository = connected(transport)
        transport.replies["provider.auth.list"] = { data("list-result-active-login") }
        transport.failures["provider.auth.login.status"] = Wire.objectOf("code" to "not_found", "message" to "gone")
        repository.browseProviders("host")
        runCurrent()
        assertNull(repository.providers().flow)
        assertEquals(R.string.providers_login_lost, repository.providers().notice?.message)
    }

    @Test
    fun aForeignLoginOnlyShowsABanner() = runTest {
        val transport = Transport()
        val repository = connected(transport)
        val list = data("list-result-active-login")
        val foreign = JsonObject(list + ("login" to JsonObject(list.obj("login") + ("own" to JsonPrimitive(false)))))
        transport.replies["provider.auth.list"] = { foreign }
        repository.browseProviders("host")
        runCurrent()
        assertNull(repository.providers().flow)
        assertEquals("anthropic", repository.providers().other?.providerId)
        assertTrue(transport.of("provider.auth.login.status").isEmpty())
        repository.startLogin("anthropic", ProviderAuthMethod.OAUTH, false)
        runCurrent()
        assertTrue(transport.of("provider.auth.login.start").isEmpty())
    }

    @Test
    fun aStrayEventForAnotherLoginChangesNothing() = runTest {
        val transport = Transport()
        val repository = connected(transport)
        repository.browseProviders("host")
        runCurrent()
        val before = repository.providers()
        transport.event(valid("prompt-secret"))
        transport.event(valid("finished-failed"))
        assertEquals(before, repository.providers())
    }


    @Test
    fun anInvalidLoginEventIsDroppedAndTriggersRecovery() = runTest {
        val transport = Transport()
        val repository = connected(transport)
        repository.browseProviders("host")
        runCurrent()
        transport.replies["provider.auth.login.start"] = { data("login-result") }
        repository.startLogin("anthropic", ProviderAuthMethod.OAUTH, false)
        runCurrent()
        transport.replies["provider.auth.login.status"] = { data("state-awaiting-input") }
        val bad = fixture.getValue("invalid").jsonArray.map { it.jsonObject }
            .first { it.text("name") == "event-auth-url-http" }.obj("payload")
        transport.event(bad)
        runCurrent()
        // The connection stays up and the login is re-read from the host.
        assertTrue(repository.state.value.connected)
        assertNull(repository.state.value.error)
        assertEquals(1, transport.of("provider.auth.login.status").size)
        assertEquals(PromptType.MANUAL_CODE, repository.providers().flow?.prompt?.prompt?.type)
    }
}
