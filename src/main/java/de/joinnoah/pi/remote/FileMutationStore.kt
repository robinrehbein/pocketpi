package de.joinnoah.pi.remote

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.util.concurrent.ConcurrentHashMap

/** Encrypted local evidence. An external continuity gate is still required for rollback protection. */
class FileMutationStore(
    context: Context,
    private val maxEntries: Int,
    private val expectProvisioned: Boolean,
) : FileMutationStorage {
    private val directory = context.noBackupFilesDir.canonicalFile
    private val payload = File(directory, "file-mutations.enc")
    private val marker = File(directory, "file-mutations-v1.provisioned")
    private val encrypted = EncryptedFileStore(context, "file-mutations.enc", "pi-remote-file-mutations-v1", preserveUnreadable = true, preserveIncompleteCommit = true)
    override val lockIdentity: Any = payload.canonicalPath
    private val lock = locks.computeIfAbsent(lockIdentity as String) { Any() }

    init { require(maxEntries > 0) }

    override fun load(): Map<FileMutationKey, FileMutationEntry> = synchronized(lock) { read() }

    override fun save(entries: Map<FileMutationKey, FileMutationEntry>) = synchronized(lock) {
        // Never replace evidence until it has been authenticated and validated.
        read()
        val encoded = encode(entries)
        encrypted.write(encoded)
    }

    private fun read(): Map<FileMutationKey, FileMutationEntry> {
        // AtomicFile recovery can replace the base file. For unresolved mutation evidence,
        // preserve every partial/legacy artifact instead of letting a read or write recover it.
        if (File(payload.path + ".new").exists() || File(payload.path + ".bak").exists()) {
            throw IOException("Unfinished mutation payload commit")
        }
        val hasPayload = payload.exists()
        val hasMarker = marker.exists()
        if (!hasPayload && !hasMarker && !expectProvisioned) {
            val empty = encode(emptyMap())
            encrypted.write(empty)
            // A failure here leaves a payload without a marker, which subsequent reads reject.
            if (!marker.createNewFile()) throw IOException("Provisioning marker already exists")
            FileOutputStream(marker).use { it.write(MARKER); it.fd.sync() }
        } else if (!hasPayload || !hasMarker) {
            throw IOException("Mutation store provisioning evidence is missing")
        }
        if (!marker.isFile || marker.length() != MARKER.size.toLong() || !marker.readBytes().contentEquals(MARKER)) throw IOException("Invalid provisioning marker")
        if (!payload.isFile || payload.length() > MAX_BYTES.toLong() + CIPHER_OVERHEAD) {
            throw IOException("Invalid mutation payload size")
        }
        val bytes = encrypted.read() ?: throw IOException("Mutation payload is missing")
        require(bytes.size <= MAX_BYTES) { "Mutation payload is too large" }
        return decode(bytes)
    }

    private fun encode(entries: Map<FileMutationKey, FileMutationEntry>): ByteArray {
        require(entries.size <= maxEntries)
        val output = StringWriter()
        JsonWriter(output).use { w ->
            w.beginObject().name("version").value(VERSION).name("entries").beginArray()
            entries.forEach { (key, entry) ->
                validate(key, entry)
                val r = entry.request
                w.beginObject().name("routeId").value(r.routeId).name("deviceId").value(r.deviceId)
                    .name("sessionId").value(r.sessionId).name("operationId").value(r.operationId).name("epoch").value(r.epoch)
                    .name("kind").value(r.kind.name).name("path").value(r.path)
                    .name("destinationPath").value(r.destinationPath).name("baseVersion").value(r.baseVersion)
                    .name("state").value(entry.state.name).name("result")
                when (val result = entry.result) {
                    null -> w.nullValue()
                    FileMutationResult.Applied -> w.beginObject().name("type").value("APPLIED").endObject()
                    is FileMutationResult.Refused -> w.beginObject().name("type").value("REFUSED").name("reason").value(result.reason).endObject()
                }
                w.endObject()
            }
            w.endArray().endObject()
        }
        return output.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }
    }

    private fun decode(bytes: ByteArray): Map<FileMutationKey, FileMutationEntry> {
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val result = linkedMapOf<FileMutationKey, FileMutationEntry>()
        JsonReader(StringReader(text)).use { r ->
            val fields = mutableSetOf<String>()
            var version: Int? = null
            var foundEntries = false
            r.beginObject()
            while (r.hasNext()) when (val name = uniqueName(r, fields)) {
                "version" -> { require(r.peek() == JsonToken.NUMBER); require(r.nextString() == VERSION.toString()); version = VERSION }
                "entries" -> {
                    foundEntries = true
                    r.beginArray()
                    while (r.hasNext()) {
                        require(result.size < maxEntries)
                        val entry = readEntry(r)
                        val key = FileMutationKey(entry.request.deviceId, entry.request.operationId)
                        validate(key, entry)
                        require(result.put(key, entry) == null) { "Duplicate mutation" }
                    }
                    r.endArray()
                }
                else -> error("Unknown field: $name")
            }
            r.endObject()
            require(version == VERSION && foundEntries && r.peek() == JsonToken.END_DOCUMENT)
        }
        return result
    }

    private fun readEntry(r: JsonReader): FileMutationEntry {
        val fields = mutableMapOf<String, String?>()
        var result: FileMutationResult? = null
        var hasResult = false
        r.beginObject()
        while (r.hasNext()) {
            val name = r.nextName()
            require(name in ENTRY_FIELDS && !fields.containsKey(name) && !(name == "result" && hasResult))
            if (name == "result") {
                hasResult = true
                result = readResult(r)
            } else {
                fields[name] = if (r.peek() == JsonToken.NULL) { r.nextNull(); null } else {
                    require(r.peek() == JsonToken.STRING); r.nextString()
                }
            }
        }
        r.endObject()
        require(fields.keys == ENTRY_FIELDS - "result" && hasResult)
        fun required(name: String) = requireNotNull(fields[name])
        val request = FileMutationRequest(required("routeId"), required("deviceId"), required("sessionId"), required("operationId"), required("epoch"),
            FileMutationKind.valueOf(required("kind")), required("path"), fields["destinationPath"], fields["baseVersion"])
        return FileMutationEntry(request, FileMutationState.valueOf(required("state")), result)
    }

    private fun readResult(r: JsonReader): FileMutationResult? {
        if (r.peek() == JsonToken.NULL) { r.nextNull(); return null }
        val fields = mutableMapOf<String, String>()
        r.beginObject()
        while (r.hasNext()) {
            val name = r.nextName()
            require(name in setOf("type", "reason") && !fields.containsKey(name) && r.peek() == JsonToken.STRING)
            fields[name] = r.nextString()
        }
        r.endObject()
        return when (fields["type"]) {
            "APPLIED" -> { require(fields.keys == setOf("type")); FileMutationResult.Applied }
            "REFUSED" -> { require(fields.keys == setOf("type", "reason")); FileMutationResult.Refused(fields.getValue("reason")) }
            else -> error("Invalid mutation result")
        }
    }

    private fun validate(key: FileMutationKey, entry: FileMutationEntry) {
        val r = entry.request
        require(key == FileMutationKey(r.deviceId, r.operationId))
        require(listOf(r.routeId, r.deviceId, r.sessionId, r.operationId, r.epoch).all { validText(it, 1024) })
        require(validPath(r.path))
        r.destinationPath?.let { require(validPath(it)) }
        r.baseVersion?.let { require(validText(it, 1024)) }
        when (r.kind) {
            FileMutationKind.CREATE -> require(r.destinationPath == null && r.baseVersion == null)
            FileMutationKind.RENAME -> {
                val destination = requireNotNull(r.destinationPath)
                require(r.baseVersion != null && destination != r.path && destination.substringBeforeLast('/', "") == r.path.substringBeforeLast('/', ""))
            }
            FileMutationKind.DELETE -> require(r.destinationPath == null && r.baseVersion != null)
        }
        require((entry.state == FileMutationState.TERMINAL) == (entry.result != null))
        (entry.result as? FileMutationResult.Refused)?.let { require(validText(it.reason, 1024)) }
    }

    private fun validText(value: String, limit: Int): Boolean {
        if (value.isBlank() || value.toByteArray(Charsets.UTF_8).size > limit) return false
        var index = 0
        while (index < value.length) {
            val c = value[index]
            if (c.isISOControl()) return false
            if (c.isHighSurrogate()) {
                if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
                index++
            } else if (c.isLowSurrogate()) return false
            index++
        }
        return true
    }

    private fun validPath(path: String) = validText(path, 4096) && !path.startsWith('/') && '\\' !in path &&
        path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

    private fun uniqueName(reader: JsonReader, fields: MutableSet<String>): String = reader.nextName().also { require(fields.add(it)) }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        /** 2 adds each operation's host journal epoch; no version 1 store was ever written by a release. */
        private const val VERSION = 2
        private val MARKER = "pocketpi-file-mutations-v1\n".toByteArray(Charsets.UTF_8)
        private const val MAX_BYTES = 8 * 1024 * 1024
        private const val CIPHER_OVERHEAD = 12 + 16 // AES-GCM IV and tag
        private val ENTRY_FIELDS = setOf("routeId", "deviceId", "sessionId", "operationId", "epoch", "kind", "path", "destinationPath", "baseVersion", "state", "result")
    }
}
