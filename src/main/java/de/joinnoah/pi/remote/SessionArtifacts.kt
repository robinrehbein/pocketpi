package de.joinnoah.pi.remote

import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The HTML, SVG and Mermaid files the pi artifacts extension recorded for a session
 * (`session.artifacts.list`, `session.artifacts.open`; protocol README, "Session artifacts"). The
 * host advertises the capability only on the `capabilities.v2:` projects route, next to
 * [FILES_MEDIA_CAPABILITY].
 */
internal const val ARTIFACTS_CAPABILITY = "session.artifacts.v1"

internal const val MAX_ARTIFACTS_LIST_ENTRIES = 200
internal const val MAX_ARTIFACT_TITLE_BYTES = 480
internal const val MAX_ARTIFACT_BYTES = 5L * 1024 * 1024
private const val MAX_ARTIFACT_VERSION = 1_000_000L
private val ARTIFACT_ID = Regex("[A-Za-z0-9_-]{22}")
private val ARTIFACT_TIME = Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z""")
private val ARTIFACT_SHA256 = Regex("[a-f0-9]{64}")

/** C0 and C1 controls and bidi controls never belong in a one-line title. */
private val ARTIFACT_TITLE_CONTROLS = Regex("[\\u0000-\\u001f\\u007f-\\u009f\\u202a-\\u202e\\u2066-\\u2069]")

enum class ArtifactType(val wire: String, internal val mime: MediaMime) {
    HTML("html", MediaMime.HTML),
    SVG("svg", MediaMime.SVG),
    MERMAID("mermaid", MediaMime.MERMAID);

    companion object {
        internal fun fromWire(value: String): ArtifactType? = entries.firstOrNull { it.wire == value }
    }
}

/** One listed artifact. [sha256] and [bytes] describe the latest [version]. */
class SessionArtifact(
    val id: String,
    val title: String,
    val type: ArtifactType,
    val version: Int,
    val sha256: String,
    val bytes: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

class SessionArtifactList(val artifacts: List<SessionArtifact>, val truncated: Boolean)

/**
 * `/artifacts` is offered when the host advertises [ARTIFACTS_CAPABILITY] and a session is selected.
 * It only reads, so it is offered while the session runs too.
 */
internal fun canShowArtifacts(state: RemoteState): Boolean =
    state.connected && !state.loading && state.selection.sessionId != null &&
        ARTIFACTS_CAPABILITY in state.capabilities && ARTIFACTS_CAPABILITY !in state.unavailableCapabilities

internal fun validArtifactId(value: String): Boolean = ARTIFACT_ID.matches(value)

/** The fields of a `session.artifacts.open`; throws [IllegalArgumentException] when invalid. */
internal fun artifactOpenFields(sessionId: String, artifactId: String, version: Int?): Array<Pair<String, Any?>> {
    require(opaqueId(sessionId) && validArtifactId(artifactId))
    require(version == null || version in 1..MAX_ARTIFACT_VERSION)
    return if (version == null) arrayOf("sessionId" to sessionId, "artifactId" to artifactId)
    else arrayOf("sessionId" to sessionId, "artifactId" to artifactId, "version" to version)
}

private fun JsonObject.strictFlag(key: String): Boolean {
    val primitive = getValue(key) as? JsonPrimitive
    require(primitive != null && !primitive.isString)
    return requireNotNull(primitive.booleanOrNull)
}

private fun artifactTime(value: String): Long {
    require(ARTIFACT_TIME.matches(value))
    return Instant.parse(value).toEpochMilli()
}

/** Validates an `artifacts.list` result strictly; throws [IllegalArgumentException] for anything else. */
internal fun parseArtifactsList(data: JsonObject, sessionId: String): SessionArtifactList {
    Wire.keys(data, setOf("kind", "sessionId", "artifacts", "truncated"))
    require(data.text("kind") == "artifacts.list")
    require(data.text("sessionId") == sessionId)
    val raw = data.getValue("artifacts") as? JsonArray
    require(raw != null && raw.size <= MAX_ARTIFACTS_LIST_ENTRIES)
    val truncated = data.strictFlag("truncated")
    val seen = HashSet<String>()
    val artifacts =
        raw.map { element ->
            val item = element as? JsonObject
            require(item != null)
            Wire.keys(item, setOf("id", "title", "type", "version", "sha256", "bytes", "createdAt", "updatedAt"))
            val id = item.text("id")
            require(validArtifactId(id) && seen.add(id))
            val title = item.text("title")
            require(title.isNotEmpty() && title.encodeToByteArray().size <= MAX_ARTIFACT_TITLE_BYTES)
            require(!ARTIFACT_TITLE_CONTROLS.containsMatchIn(title))
            val type = requireNotNull(ArtifactType.fromWire(item.text("type")))
            val version = item.long("version")
            require(version in 1..MAX_ARTIFACT_VERSION)
            val sha256 = item.text("sha256")
            require(ARTIFACT_SHA256.matches(sha256))
            val bytes = item.long("bytes")
            require(bytes in 0..MAX_ARTIFACT_BYTES)
            SessionArtifact(
                id, title, type, version.toInt(), sha256, bytes,
                artifactTime(item.text("createdAt")), artifactTime(item.text("updatedAt")),
            )
        }
    return SessionArtifactList(artifacts, truncated)
}

/**
 * True for the logical path `artifacts/<artifactId>/<n>.<ext>` an open answers with. With a
 * [version] asked for, `<n>` is that version; the extension is checked against the media type later.
 */
internal fun artifactPathMatches(path: String, artifactId: String, version: Int?): Boolean {
    val parts = path.split('/')
    if (parts.size != 3 || parts[0] != "artifacts" || parts[1] != artifactId) return false
    val name = parts[2]
    val number = name.substringBefore('.').toLongOrNull() ?: return false
    val extension = name.substringAfter('.', "")
    return number in 1..MAX_ARTIFACT_VERSION && name.substringBefore('.') == number.toString() &&
        extension in setOf("html", "svg", "mmd") && (version == null || number == version.toLong())
}

/** Outcome of [RemoteRepository.listSessionArtifacts]. */
sealed interface SessionArtifactListResult {
    class Loaded(val list: SessionArtifactList) : SessionArtifactListResult

    /** No request was sent: the host lacks [ARTIFACTS_CAPABILITY]. */
    data object Unsupported : SessionArtifactListResult

    /** Not allowed, or an answer that broke the protocol; asking again gives the same. */
    data object Unavailable : SessionArtifactListResult

    /** Offline, a dropped connection, a timeout or a failing host; asking again may work. */
    data object Failed : SessionArtifactListResult
}

/** Outcome of [RemoteRepository.openSessionArtifact]; loaded content passed the length and SHA-256 checks. */
sealed interface SessionArtifactOpenResult {
    /** An HTML document or Mermaid source, decoded as strict UTF-8. */
    class Text(val type: ArtifactType, val loaded: ProjectArtifactResult.Loaded) : SessionArtifactOpenResult

    class Svg(val image: ProjectImageResult.Loaded) : SessionArtifactOpenResult

    /** No request was sent: the host lacks [ARTIFACTS_CAPABILITY]. */
    data object Unsupported : SessionArtifactOpenResult

    /** Not found, not allowed, or not valid; asking again returns the same answer. */
    data object Unavailable : SessionArtifactOpenResult

    data object TooLarge : SessionArtifactOpenResult

    /** The host holds it but the bytes are not what its type promises, here or there. */
    data object NotAnArtifact : SessionArtifactOpenResult

    data object Busy : SessionArtifactOpenResult

    data object ConnectionFailure : SessionArtifactOpenResult
}
