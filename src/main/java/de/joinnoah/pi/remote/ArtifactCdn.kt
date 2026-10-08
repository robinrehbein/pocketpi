package de.joinnoah.pi.remote

import android.content.Context
import okhttp3.Cache
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/*
 * The one hole in the artifact sandbox. The WebView itself still has no network: its proxy is dead and
 * every request goes through shouldInterceptRequest. A request for a library on a fixed CDN list is
 * answered from here instead, by the app, with a request the page cannot shape. The WebView never
 * connects to those hosts; it only receives the bytes this file decided to hand over.
 *
 * What the page controls is the URL, and the URL is held to exact hosts: https only, port 443, no
 * credentials, one of [ARTIFACT_CDN_SCRIPT_HOSTS] or [ARTIFACT_CDN_FONT_HOSTS]. Nothing else of the
 * page's request is forwarded: no headers, cookies, Referer or Origin.
 */

/** Hosts that serve scripts, styles, images, data and fonts. These also go into script-src and connect-src. */
internal val ARTIFACT_CDN_SCRIPT_HOSTS =
    listOf("cdnjs.cloudflare.com", "cdn.jsdelivr.net", "unpkg.com", "cdn.tailwindcss.com", "code.jquery.com")

/** Google Fonts: the stylesheet host and the font file host. */
internal const val ARTIFACT_CDN_FONT_CSS_HOST = "fonts.googleapis.com"
internal const val ARTIFACT_CDN_FONT_FILE_HOST = "fonts.gstatic.com"

private val allowedHosts: Set<String> =
    (ARTIFACT_CDN_SCRIPT_HOSTS + ARTIFACT_CDN_FONT_CSS_HOST + ARTIFACT_CDN_FONT_FILE_HOST).toSet()

private val authorityCharacters = Regex("[a-z0-9.:\\-]+")

/**
 * Whether [url] names a resource on the CDN allowlist. Exact host, `https` only, port 443 only, no
 * user info. Strict on purpose: anything this does not recognise exactly (upper case, a trailing dot,
 * a backslash, whitespace or control characters, punycode or other look-alikes) is refused, and the
 * WebView, which canonicalises URLs, never sends such a form for a legitimate request.
 */
internal fun artifactCdnAllowed(url: String): Boolean {
    if (!url.startsWith("https://")) return false
    if (url.any { it <= ' ' || it == '\\' || it == '\u007f' }) return false
    val rest = url.removePrefix("https://")
    val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
    if (authority.isEmpty() || !authorityCharacters.matches(authority)) return false
    val host = authority.substringBefore(':')
    val port = if (':' in authority) authority.substringAfter(':') else null
    if (port != null && port != "443") return false
    return host in allowedHosts
}

/** What the sandbox lets through from the CDN, as a media type without parameters. */
internal fun artifactCdnContentTypeAllowed(mediaType: String?): Boolean {
    val type = mediaType?.substringBefore(';')?.trim()?.lowercase() ?: return false
    return type.startsWith("font/") || type.startsWith("image/") || type in allowedContentTypes
}

private val allowedContentTypes =
    setOf(
        "text/javascript", "application/javascript", "application/x-javascript", "application/ecmascript",
        "text/ecmascript", "text/css", "application/json", "application/wasm", "text/plain",
        "application/font-woff", "application/font-woff2",
    )

/** The charset parameter of a Content-Type value, or null. */
internal fun artifactCdnCharset(contentType: String?): String? =
    contentType
        ?.split(';')
        ?.drop(1)
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim('"', ' ')
        ?.takeIf { it.isNotEmpty() }

internal const val ARTIFACT_CDN_MAX_REDIRECTS = 3
internal const val ARTIFACT_CDN_MAX_RESOURCE_BYTES = 8L * 1024 * 1024
internal const val ARTIFACT_CDN_MAX_PAGE_BYTES = 32L * 1024 * 1024
internal const val ARTIFACT_CDN_CONNECT_TIMEOUT_SECONDS = 10L
internal const val ARTIFACT_CDN_READ_TIMEOUT_SECONDS = 20L
internal const val ARTIFACT_CDN_CACHE_BYTES = 50L * 1024 * 1024

/** One response from the network, before any policy. A redirect is reported, never followed. */
internal class ArtifactCdnRaw(
    val status: Int,
    /** The `Location` header, for a redirect. */
    val location: String?,
    val contentType: String?,
    val contentLength: Long,
    /** Reads at most `limit` bytes, or null when the body is longer. */
    val readBody: (limit: Long) -> ByteArray?,
    val close: () -> Unit = {},
)

/** A plain GET of [url]. Implementations must follow no redirect and send nothing the caller did not pass. */
internal interface ArtifactCdnTransport {
    @Throws(IOException::class)
    fun get(url: String, userAgent: String, budget: ArtifactCdnBudget): ArtifactCdnRaw
}

/** Per-view state: bytes still allowed for this page load, and cancellation when the view is disposed. */
internal class ArtifactCdnBudget(private val totalBytes: Long = ARTIFACT_CDN_MAX_PAGE_BYTES) {
    private var used = 0L
    private val cancellations = mutableListOf<() -> Unit>()

    @Volatile
    var cancelled = false
        private set

    @Synchronized
    fun remaining(): Long = totalBytes - used

    @Synchronized
    fun spend(bytes: Long) {
        used += bytes
    }

    /** Runs [action] if the view is disposed while a request is in flight. Returns how to unregister. */
    @Synchronized
    fun onCancel(action: () -> Unit): () -> Unit {
        if (cancelled) action() else cancellations += action
        return { synchronized(this) { cancellations -= action } }
    }

    fun cancel() {
        val pending =
            synchronized(this) {
                cancelled = true
                cancellations.toList().also { cancellations.clear() }
            }
        pending.forEach { runCatching(it) }
    }
}

/** What the page receives for an allowlisted request. */
internal sealed interface ArtifactCdnReply {
    class Ok(val mediaType: String, val charset: String?, val body: ByteArray) : ArtifactCdnReply

    /** Not delivered. [status] is the HTTP status the page sees (always with an empty body). */
    class Refused(val status: Int) : ArtifactCdnReply
}

/** Answers allowlisted requests. Tests and the emulator probe substitute their own. */
internal fun interface ArtifactCdnFetcher {
    fun fetch(url: String, userAgent: String, budget: ArtifactCdnBudget): ArtifactCdnReply
}

/**
 * The policy over a [ArtifactCdnTransport]: the allowlist on the request and on every redirect hop (at
 * most [ARTIFACT_CDN_MAX_REDIRECTS]), 200 only, a content-type filter and the size caps. Blocking;
 * runs on the WebView's request thread.
 */
internal class ArtifactCdnPolicyFetcher(private val transport: ArtifactCdnTransport) : ArtifactCdnFetcher {
    override fun fetch(url: String, userAgent: String, budget: ArtifactCdnBudget): ArtifactCdnReply {
        var current = url
        var hops = 0
        while (true) {
            if (!artifactCdnAllowed(current) || budget.cancelled) return ArtifactCdnReply.Refused(403)
            val raw =
                try {
                    transport.get(current, userAgent, budget)
                } catch (_: IOException) {
                    return ArtifactCdnReply.Refused(504)
                } catch (_: RuntimeException) {
                    return ArtifactCdnReply.Refused(504)
                }
            try {
                if (raw.status in REDIRECT_STATUSES) {
                    if (++hops > ARTIFACT_CDN_MAX_REDIRECTS) return ArtifactCdnReply.Refused(403)
                    val target = resolve(current, raw.location) ?: return ArtifactCdnReply.Refused(403)
                    current = target
                    continue
                }
                if (raw.status != 200) return ArtifactCdnReply.Refused(404)
                if (!artifactCdnContentTypeAllowed(raw.contentType)) return ArtifactCdnReply.Refused(403)
                val limit = minOf(ARTIFACT_CDN_MAX_RESOURCE_BYTES, budget.remaining())
                if (limit <= 0 || raw.contentLength > limit) return ArtifactCdnReply.Refused(403)
                val body =
                    try {
                        raw.readBody(limit)
                    } catch (_: IOException) {
                        return ArtifactCdnReply.Refused(504)
                    } ?: return ArtifactCdnReply.Refused(403)
                budget.spend(body.size.toLong())
                return ArtifactCdnReply.Ok(
                    raw.contentType!!.substringBefore(';').trim().lowercase(),
                    artifactCdnCharset(raw.contentType),
                    body,
                )
            } finally {
                runCatching(raw.close)
            }
        }
    }

    /** An absolute or root-relative `Location`, resolved against the current hop; the allowlist check runs on it next. */
    private fun resolve(current: String, location: String?): String? {
        val target = location?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            target.startsWith("https://") -> target
            target.startsWith("//") -> "https:$target"
            target.startsWith("/") -> "https://" + current.removePrefix("https://").takeWhile { it != '/' && it != '?' && it != '#' } + target
            else -> null
        }
    }

    private companion object {
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    }
}

/** The OkHttp transport: a client of its own, with no cookies, no interceptors, TLS only, and an app-private cache. */
internal class OkHttpArtifactCdnTransport(cacheDirectory: File) : ArtifactCdnTransport {
    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .connectTimeout(ARTIFACT_CDN_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(ARTIFACT_CDN_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .cache(Cache(cacheDirectory, ARTIFACT_CDN_CACHE_BYTES))
            .build()

    override fun get(url: String, userAgent: String, budget: ArtifactCdnBudget): ArtifactCdnRaw {
        // Built from the URL and a fixed User-Agent only; no header of the page's request is passed in.
        val request = Request.Builder().url(url).get().header("User-Agent", userAgent).build()
        val call = client.newCall(request)
        val unregister = budget.onCancel { call.cancel() }
        val response =
            try {
                call.execute()
            } catch (e: IOException) {
                unregister()
                throw e
            }
        val body = response.body
        return ArtifactCdnRaw(
            status = response.code,
            location = response.header("Location"),
            contentType = response.header("Content-Type"),
            contentLength = body.contentLength(),
            readBody = { limit ->
                val source = body.source()
                // Request one byte more than the limit to tell "exactly at the limit" from "longer".
                source.request(limit + 1)
                if (source.buffer.size > limit) null else source.readByteArray()
            },
            close = {
                unregister()
                response.close()
            },
        )
    }
}

/** The fetcher the app uses: created on first use, with its cache under `cacheDir/artifact-cdn`. */
internal object ArtifactCdn {
    @Volatile
    private var instance: ArtifactCdnFetcher? = null

    fun fetcher(context: Context): ArtifactCdnFetcher =
        instance ?: synchronized(this) {
            instance ?: ArtifactCdnPolicyFetcher(OkHttpArtifactCdnTransport(File(context.applicationContext.cacheDir, "artifact-cdn")))
                .also { instance = it }
        }
}
