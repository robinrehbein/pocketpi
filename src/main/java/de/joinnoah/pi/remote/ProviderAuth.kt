package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Provider login and logout on the Mac (`provider.auth.v1`). The host runs pi's own login flows;
 * this file holds the wire models, validators that mirror the host's `provider-auth.ts`, and the
 * reducer for the one login this device shows. Nothing here holds a typed secret: answers are
 * passed straight to the repository call and dropped.
 */
internal const val PROVIDER_AUTH_CAPABILITY = "provider.auth.v1"

internal const val MAX_PROVIDER_AUTH_PROVIDERS = 256
internal const val MAX_PROVIDER_AUTH_LIST_BYTES = 96 * 1024
internal const val MAX_PROVIDER_AUTH_ANSWER_BYTES = 8 * 1024
private const val MAX_MESSAGE_BYTES = 1024
private const val MAX_URL_BYTES = 2048
private const val MAX_CODE_BYTES = 64
private const val MAX_OPTIONS = 32
private const val MAX_LINKS = 8

enum class ProviderAuthMethod(val wire: String) {
    OAUTH("oauth"),
    API_KEY("api_key"),
}

/** Where a configured credential comes from. The host reports `stored` and `config` today. */
enum class ProviderKeySource(val wire: String) {
    STORED("stored"),
    ENVIRONMENT("environment"),
    RUNTIME("runtime"),
    CONFIG("config"),
}

data class ProviderStatus(
    val configured: Boolean,
    val source: ProviderKeySource? = null,
    val type: ProviderAuthMethod? = null,
)

data class AuthProvider(
    val id: String,
    val name: String,
    val methods: List<ProviderAuthMethod>,
    val status: ProviderStatus,
) {
    /** Only a stored credential can be replaced or removed from the phone. */
    val storedLogin: Boolean
        get() = status.configured && (status.source == null || status.source == ProviderKeySource.STORED)
}

enum class LoginState(val wire: String) {
    RUNNING("running"),
    AWAITING_INPUT("awaiting_input"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    TIMEOUT("timeout");

    val finished: Boolean
        get() = this != RUNNING && this != AWAITING_INPUT
}

enum class LoginError(val wire: String) {
    CANCELLED("cancelled"),
    TIMEOUT("timeout"),
    DENIED("denied"),
    INVALID_CODE("invalid_code"),
    NETWORK("network"),
    STORE_FAILED("store_failed"),
    FAILED("failed"),
}

enum class PromptType(val wire: String) {
    TEXT("text"),
    SECRET("secret"),
    MANUAL_CODE("manual_code"),
    SELECT("select"),
}

enum class PromptCloseReason(val wire: String) {
    ANSWERED("answered"),
    SUPERSEDED("superseded"),
    CANCELLED("cancelled"),
}

data class SelectOption(val id: String, val label: String, val description: String?)

data class AuthPrompt(
    val type: PromptType,
    val message: String,
    val placeholder: String?,
    val options: List<SelectOption>,
)

data class PendingPrompt(val promptId: String, val prompt: AuthPrompt, val expiresAt: Long)

data class AuthLink(val url: String, val label: String?)

sealed interface AuthEvent {
    data class AuthUrl(val url: String, val instructions: String?) : AuthEvent

    data class DeviceCode(
        val userCode: String,
        val verificationUri: String,
        val intervalSeconds: Long?,
        val expiresInSeconds: Long?,
    ) : AuthEvent

    data class Info(val message: String, val links: List<AuthLink>) : AuthEvent

    data class Progress(val message: String) : AuthEvent
}

data class ActiveLogin(
    val loginId: String,
    val providerId: String,
    val state: LoginState,
    val own: Boolean,
)

data class ProviderList(
    val providers: List<AuthProvider>,
    val login: ActiveLogin?,
    val truncated: Boolean,
)

data class LoginStarted(
    val loginId: String,
    val providerId: String,
    val method: ProviderAuthMethod,
    val expiresAt: Long,
)

data class LoginStatus(
    val loginId: String,
    val providerId: String,
    val state: LoginState,
    val prompt: PendingPrompt?,
    val lastEvent: AuthEvent?,
    val error: LoginError?,
    val modelSync: Boolean?,
)

/** A `host.event` of a login this device started. */
internal sealed interface ProviderAuthUpdate {
    val loginId: String

    data class Event(override val loginId: String, val event: AuthEvent) : ProviderAuthUpdate

    data class Prompt(override val loginId: String, val pending: PendingPrompt) : ProviderAuthUpdate

    data class PromptClosed(
        override val loginId: String,
        val promptId: String,
        val reason: PromptCloseReason,
    ) : ProviderAuthUpdate

    data class Finished(
        override val loginId: String,
        val providerId: String,
        val state: LoginState,
        val error: LoginError?,
        /** True when the Mac saved the credential but could not refresh its model list. */
        val modelStale: Boolean?,
    ) : ProviderAuthUpdate
}

/** A message the user sees next to a provider or flow, with the provider name it names, if any. */
data class ProviderNotice(val message: Int, val provider: String? = null)

/**
 * The login this device shows. [method] is null when it was rebuilt from a list after the app
 * restarted. Holds no typed value: a secret lives only in the composable that collects it.
 */
data class LoginFlow(
    val loginId: String,
    val providerId: String,
    val method: ProviderAuthMethod?,
    val state: LoginState = LoginState.RUNNING,
    val prompt: PendingPrompt? = null,
    val deviceCode: AuthEvent.DeviceCode? = null,
    /** When the device code arrived live; null for a code recovered after a reconnect. */
    val deviceCodeAt: Long? = null,
    val authUrl: AuthEvent.AuthUrl? = null,
    val info: AuthEvent.Info? = null,
    val progress: String? = null,
    val error: LoginError? = null,
    val modelStale: Boolean = false,
    val answering: Boolean = false,
    val cancelling: Boolean = false,
    val notice: ProviderNotice? = null,
    /** Counts live updates, so a status result requested earlier cannot overwrite newer state. */
    val live: Int = 0,
) {
    val finished: Boolean
        get() = state.finished
}

/** The provider list of one host and its login flow. */
data class ProviderAuthState(
    val routeId: String? = null,
    val providers: List<AuthProvider> = emptyList(),
    val truncated: Boolean = false,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: Int? = null,
    val notice: ProviderNotice? = null,
    /** A start or logout is in flight. */
    val working: Boolean = false,
    /** A login another device started; this one only shows it. */
    val other: ActiveLogin? = null,
    val flow: LoginFlow? = null,
) {
    /** A login is running on the host, ours or not; the host allows one at a time. */
    val busy: Boolean
        get() = other != null || flow?.finished == false
}

internal fun canManageProviders(state: RemoteState): Boolean =
    state.connected && PROVIDER_AUTH_CAPABILITY in state.capabilities

private val UNSAFE_TEXT = Regex("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]")
private val URL_FORBIDDEN = Regex("[\\s\\p{Cc}\\p{Cf}\\p{Z}]")
// A canonical https URL (WHATWG href) always has a path, at least "/".
private val HTTPS_URL = Regex("^https://([^/?#@\\\\]+)(/.*)$", RegexOption.DOT_MATCHES_ALL)

private fun bytes(value: String) = value.toByteArray().size

private fun JsonObject.string(key: String, max: Int, empty: Boolean = false): String =
    text(key).also { require((empty || it.isNotEmpty()) && bytes(it) <= max) }

private fun JsonObject.safeText(key: String, max: Int): String =
    string(key, max).also { require(!UNSAFE_TEXT.containsMatchIn(it)) }

private fun JsonObject.optionalSafeText(key: String, max: Int): String? =
    if (key in this) safeText(key, max) else null

private fun JsonObject.count(key: String, max: Long): Long =
    long(key).also { require(it in 0..max) }

private fun JsonObject.optionalCount(key: String, max: Long): Long? =
    if (key in this) count(key, max) else null

/** A 128-bit random ID as the host mints them for logins and prompts. */
private fun JsonObject.randomId(key: String): String =
    text(key).also {
        require(it.length <= 22)
        Wire.decode(it, 16)
    }

/** 1..128 UTF-8 bytes without whitespace or control characters, as the host's `isProviderId`. */
internal fun validProviderId(value: String): Boolean =
    value.isNotEmpty() && bytes(value) <= 128 && value.none { it.code <= 0x20 || it.code == 0x7f }

private fun JsonObject.providerId(key: String): String =
    text(key).also { require(validProviderId(it)) }

/** Whether [url] is an `https:` address the app may open: no whitespace, control characters or userinfo. */
internal fun validHttpsUrl(url: String): Boolean {
    if (url.isEmpty() || bytes(url) > MAX_URL_BYTES || URL_FORBIDDEN.containsMatchIn(url)) return false
    val authority = HTTPS_URL.matchEntire(url)?.groupValues?.get(1) ?: return false
    val host = if (authority.startsWith("[")) authority.substringBefore(']') else authority.substringBeforeLast(':')
    return host.isNotEmpty() && host != "["
}

private fun JsonObject.httpsUrl(key: String): String =
    text(key).also { require(validHttpsUrl(it)) }

private inline fun <reified T : Enum<T>> enumOf(value: String, wire: (T) -> String): T =
    requireNotNull(enumValues<T>().firstOrNull { wire(it) == value })

private fun method(value: String) = enumOf<ProviderAuthMethod>(value) { it.wire }

private fun loginState(value: String) = enumOf<LoginState>(value) { it.wire }

private fun loginError(value: String) = enumOf<LoginError>(value) { it.wire }

private fun JsonObject.modelSync(key: String): Boolean? =
    if (key in this) {
        when (text(key)) {
            "ok" -> false
            "stale" -> true
            else -> throw IllegalArgumentException("Invalid modelSync")
        }
    } else null

private fun JsonObject.arrayOf(key: String): List<JsonObject> {
    require(getValue(key) is JsonArray)
    return array(key)
}

private fun JsonObject.stringList(key: String): List<String> {
    val value = getValue(key)
    require(value is JsonArray)
    return value.map {
        require(it is JsonPrimitive && it.isString)
        it.content
    }
}

internal fun parseAuthPrompt(value: JsonObject): AuthPrompt {
    Wire.keys(value, setOf("type", "message"), setOf("placeholder", "options"))
    val type = enumOf<PromptType>(value.text("type")) { it.wire }
    val message = value.safeText("message", MAX_MESSAGE_BYTES)
    val placeholder = value.optionalSafeText("placeholder", 256)
    require((type == PromptType.SELECT) == ("options" in value))
    val options =
        if ("options" in value) {
            value.arrayOf("options").map {
                Wire.keys(it, setOf("id", "label"), setOf("description"))
                SelectOption(it.safeText("id", 128), it.safeText("label", 256), it.optionalSafeText("description", 512))
            }.also { require(it.size in 1..MAX_OPTIONS) }
        } else emptyList()
    return AuthPrompt(type, message, placeholder, options)
}

private fun parsePending(value: JsonObject): PendingPrompt {
    Wire.keys(value, setOf("promptId", "prompt", "expiresAt"))
    return PendingPrompt(value.randomId("promptId"), parseAuthPrompt(value.obj("prompt")), value.count("expiresAt", Long.MAX_VALUE))
}

internal fun parseAuthEvent(value: JsonObject): AuthEvent =
    when (value.text("kind")) {
        "auth_url" -> {
            Wire.keys(value, setOf("kind", "url"), setOf("instructions"))
            AuthEvent.AuthUrl(value.httpsUrl("url"), value.optionalSafeText("instructions", MAX_MESSAGE_BYTES))
        }
        "device_code" -> {
            Wire.keys(
                value,
                setOf("kind", "userCode", "verificationUri"),
                setOf("intervalSeconds", "expiresInSeconds"),
            )
            AuthEvent.DeviceCode(
                value.safeText("userCode", MAX_CODE_BYTES),
                value.httpsUrl("verificationUri"),
                value.optionalCount("intervalSeconds", 3600),
                value.optionalCount("expiresInSeconds", 86400),
            )
        }
        "info" -> {
            Wire.keys(value, setOf("kind", "message"), setOf("links"))
            val links =
                if ("links" in value) {
                    value.arrayOf("links").map {
                        Wire.keys(it, setOf("url"), setOf("label"))
                        AuthLink(it.httpsUrl("url"), it.optionalSafeText("label", 256))
                    }.also { require(it.size <= MAX_LINKS) }
                } else emptyList()
            AuthEvent.Info(value.safeText("message", MAX_MESSAGE_BYTES), links)
        }
        "progress" -> {
            Wire.keys(value, setOf("kind", "message"))
            AuthEvent.Progress(value.safeText("message", MAX_MESSAGE_BYTES))
        }
        else -> throw IllegalArgumentException("Unknown auth event")
    }

/** Validates a `provider.auth.list` result; [data] is the result's `data` object. */
internal fun parseProviderList(data: JsonObject): ProviderList {
    require(data.text("kind") == "provider.auth.list")
    Wire.keys(data, setOf("kind", "providers", "truncated"), setOf("login"))
    val providers =
        data.arrayOf("providers").map { entry ->
            Wire.keys(entry, setOf("id", "name", "methods", "status"))
            val names = entry.stringList("methods")
            require(names.size <= 2 && names.toSet().size == names.size)
            val status = entry.obj("status")
            Wire.keys(status, setOf("configured"), setOf("source", "type"))
            val configured = status.flag("configured")
            require(configured || ("source" !in status && "type" !in status))
            AuthProvider(
                id = entry.providerId("id"),
                name = entry.safeText("name", 256),
                methods = names.map(::method),
                status =
                    ProviderStatus(
                        configured,
                        if ("source" in status) enumOf<ProviderKeySource>(status.text("source")) { it.wire } else null,
                        if ("type" in status) method(status.text("type")) else null,
                    ),
            )
        }
    require(providers.size <= MAX_PROVIDER_AUTH_PROVIDERS)
    val truncated = data.flag("truncated")
    val login =
        if ("login" in data) {
            val value = data.obj("login")
            Wire.keys(value, setOf("loginId", "providerId", "state", "own"))
            ActiveLogin(
                value.randomId("loginId"),
                value.providerId("providerId"),
                loginState(value.text("state")),
                value.flag("own"),
            )
        } else null
    require(bytes(data.toString()) <= MAX_PROVIDER_AUTH_LIST_BYTES)
    return ProviderList(providers, login, truncated)
}

/** Validates a `provider.auth.login` (start) result. */
internal fun parseLoginStarted(data: JsonObject): LoginStarted {
    require(data.text("kind") == "provider.auth.login")
    Wire.keys(data, setOf("kind", "loginId", "providerId", "method", "expiresAt"))
    return LoginStarted(
        data.randomId("loginId"),
        data.providerId("providerId"),
        method(data.text("method")),
        data.count("expiresAt", Long.MAX_VALUE),
    )
}

/** Validates a `provider.auth.login.state` (status) result. */
internal fun parseLoginStatus(data: JsonObject): LoginStatus {
    require(data.text("kind") == "provider.auth.login.state")
    Wire.keys(
        data,
        setOf("kind", "loginId", "providerId", "state"),
        setOf("prompt", "lastEvent", "error", "modelSync"),
    )
    val state = loginState(data.text("state"))
    val prompt = if ("prompt" in data) parsePending(data.obj("prompt")) else null
    require(prompt == null || state == LoginState.AWAITING_INPUT)
    val error = if ("error" in data) loginError(data.text("error")) else null
    require(error == null || state.finished && state != LoginState.SUCCEEDED)
    val modelSync = data.modelSync("modelSync")
    require(modelSync == null || state == LoginState.SUCCEEDED)
    return LoginStatus(
        data.randomId("loginId"),
        data.providerId("providerId"),
        state,
        prompt,
        if ("lastEvent" in data) parseAuthEvent(data.obj("lastEvent")) else null,
        error,
        modelSync,
    )
}

/** Validates a `provider.auth.logout` result; returns whether the model list is stale. */
internal fun parseLogout(data: JsonObject, providerId: String): Boolean {
    require(data.text("kind") == "provider.auth.logout")
    Wire.keys(data, setOf("kind", "providerId", "modelSync"))
    require(data.providerId("providerId") == providerId)
    return checkNotNull(data.modelSync("modelSync"))
}

/** Validates the `accepted` result of an answer or cancel. */
internal fun requireAccepted(data: JsonObject) = require(data.text("kind") == "accepted")

/**
 * Validates a `provider.auth.*` `host.event`. Returns null for a kind that is not a provider
 * login event; an unknown `provider.auth.` kind or an invalid value throws.
 */
internal fun providerAuthEvent(payload: JsonObject): ProviderAuthUpdate? {
    val kind = payload.text("kind")
    if (!kind.startsWith("provider.auth.")) return null
    return when (kind) {
        "provider.auth.event" -> {
            Wire.keys(payload, setOf("type", "kind", "loginId", "event"))
            ProviderAuthUpdate.Event(payload.randomId("loginId"), parseAuthEvent(payload.obj("event")))
        }
        "provider.auth.prompt" -> {
            Wire.keys(payload, setOf("type", "kind", "loginId", "promptId", "prompt", "expiresAt"))
            ProviderAuthUpdate.Prompt(
                payload.randomId("loginId"),
                PendingPrompt(
                    payload.randomId("promptId"),
                    parseAuthPrompt(payload.obj("prompt")),
                    payload.count("expiresAt", Long.MAX_VALUE),
                ),
            )
        }
        "provider.auth.prompt.closed" -> {
            Wire.keys(payload, setOf("type", "kind", "loginId", "promptId", "reason"))
            ProviderAuthUpdate.PromptClosed(
                payload.randomId("loginId"),
                payload.randomId("promptId"),
                enumOf<PromptCloseReason>(payload.text("reason")) { it.wire },
            )
        }
        "provider.auth.finished" -> {
            Wire.keys(payload, setOf("type", "kind", "loginId", "providerId", "state"), setOf("error", "modelSync"))
            val state = loginState(payload.text("state"))
            require(state.finished)
            val error = if ("error" in payload) loginError(payload.text("error")) else null
            require(error == null || state != LoginState.SUCCEEDED)
            val modelSync = payload.modelSync("modelSync")
            require(modelSync == null || state == LoginState.SUCCEEDED)
            ProviderAuthUpdate.Finished(
                payload.randomId("loginId"),
                payload.providerId("providerId"),
                state,
                error,
                modelSync,
            )
        }
        else -> throw IllegalArgumentException("Unknown provider auth event")
    }
}

private fun LoginFlow.withEvent(event: AuthEvent, now: Long?): LoginFlow =
    when (event) {
        is AuthEvent.AuthUrl -> copy(authUrl = event)
        // The same code delivered again (a status after the browser trip) keeps its arrival time.
        is AuthEvent.DeviceCode ->
            copy(deviceCode = event, deviceCodeAt = if (event == deviceCode && deviceCodeAt != null) deviceCodeAt else now)
        is AuthEvent.Info -> copy(info = event)
        is AuthEvent.Progress -> copy(progress = event.message)
    }

/**
 * Applies a live [update]. A finished flow no longer changes; the host reports the real outcome
 * even for a login that was cancelled, so [LoginFlow.state] and [LoginFlow.error] come from it.
 */
internal fun LoginFlow.updatedBy(update: ProviderAuthUpdate, now: Long): LoginFlow {
    if (finished || update.loginId != loginId) return this
    val next = applied(update, now)
    return if (next == this) this else next.bumped()
}

private fun LoginFlow.bumped() = copy(live = live + 1)

private fun LoginFlow.applied(update: ProviderAuthUpdate, now: Long): LoginFlow {
    return when (update) {
        is ProviderAuthUpdate.Event -> withEvent(update.event, now)
        is ProviderAuthUpdate.Prompt ->
            copy(state = LoginState.AWAITING_INPUT, prompt = update.pending, answering = false, notice = null)
        is ProviderAuthUpdate.PromptClosed ->
            if (prompt?.promptId != update.promptId) this
            else copy(state = LoginState.RUNNING, prompt = null, answering = false)
        is ProviderAuthUpdate.Finished ->
            copy(
                state = update.state,
                prompt = null,
                answering = false,
                cancelling = false,
                error = update.error,
                modelStale = update.modelStale == true,
                notice = null,
            )
    }
}

/**
 * Applies a `login.status` result, the recovery after an app switch or reconnect. A recovered
 * device code has no arrival time, so it shows no countdown.
 */
internal fun LoginFlow.updatedBy(status: LoginStatus, requestedAtLive: Int = live): LoginFlow {
    // A live event since the request is newer than this snapshot.
    if (finished || status.loginId != loginId || requestedAtLive != live) return this
    val base =
        copy(
            state = status.state,
            prompt = status.prompt,
            answering = answering && status.prompt?.promptId == prompt?.promptId,
            cancelling = cancelling && !status.state.finished,
            error = status.error,
            modelStale = status.modelSync == true,
        )
    return status.lastEvent?.let { base.withEvent(it, null) } ?: base
}

/** [promptId] was answered and accepted: no second answer for it, whatever the events say. */
internal fun LoginFlow.answered(promptId: String): LoginFlow =
    if (finished || prompt?.promptId != promptId) copy(answering = false)
    else copy(state = LoginState.RUNNING, prompt = null, answering = false, live = live + 1)

/** The flow of a login found in a list after the app lost it. */
internal fun ActiveLogin.toFlow(): LoginFlow = LoginFlow(loginId, providerId, null, state)

/** Whether the flow shows something to keep out of screenshots and the recents thumbnail. */
internal fun LoginFlow.needsSecureWindow(): Boolean =
    !finished &&
        (prompt?.prompt?.type.let { it == PromptType.SECRET || it == PromptType.MANUAL_CODE } ||
            deviceCode != null ||
            authUrl != null)

/** Applies a `provider.auth.list` result to [this] state of the same host. */
internal fun ProviderAuthState.withList(list: ProviderList): ProviderAuthState {
    val active = list.login?.takeIf { !it.state.finished }
    val own = active?.takeIf { it.own }
    return copy(
        providers = list.providers,
        truncated = list.truncated,
        loaded = true,
        loading = false,
        error = null,
        other = active?.takeIf { !it.own },
        flow = if (own != null && flow?.loginId != own.loginId) own.toFlow() else flow,
    )
}

/** The message for an error code of a provider command. */
internal fun providerErrorMessage(error: Throwable): Int =
    when (error.message) {
        "login_in_progress" -> R.string.providers_error_in_progress
        "rate_limited" -> R.string.providers_error_rate_limited
        "exists" -> R.string.providers_error_exists
        "unsupported" -> R.string.providers_error_unsupported
        "not_found" -> R.string.providers_error_not_found
        "already_resolved" -> R.string.providers_error_resolved
        "forbidden" -> R.string.providers_error_forbidden
        "invalid_request" -> R.string.providers_error_invalid
        "offline" -> R.string.providers_error_offline
        "internal" -> R.string.providers_error_internal
        else -> R.string.remote_request_error
    }

/** The reason a login ended without success. [error] wins; without one the [state] decides. */
internal fun loginFailureMessage(state: LoginState, error: LoginError?): Int =
    when (error) {
        LoginError.CANCELLED -> R.string.providers_failure_cancelled
        LoginError.TIMEOUT -> R.string.providers_failure_timeout
        LoginError.DENIED -> R.string.providers_failure_denied
        LoginError.INVALID_CODE -> R.string.providers_failure_invalid_code
        LoginError.NETWORK -> R.string.providers_failure_network
        LoginError.STORE_FAILED -> R.string.providers_failure_store_failed
        LoginError.FAILED -> R.string.providers_failure_failed
        null ->
            when (state) {
                LoginState.CANCELLED -> R.string.providers_failure_cancelled
                LoginState.TIMEOUT -> R.string.providers_failure_timeout
                else -> R.string.providers_failure_failed
            }
    }
