package de.joinnoah.pi.remote

/**
 * A unified diff of [original] to [edited] for [path] with [context] lines around each change, or
 * null when both are equal.
 */
fun unifiedDiff(path: String, original: String, edited: String, context: Int = 3): String? =
    if (original == edited) null else TODO("Task D")
