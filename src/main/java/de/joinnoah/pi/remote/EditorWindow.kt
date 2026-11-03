package de.joinnoah.pi.remote

/** A half-open range into a String, measured in UTF-16 code units. */
internal data class EditorWindow(val start: Int, val end: Int)

internal object EditorWindows {
    const val MAX_UNITS = 2048

    fun sectionCount(text: String): Int =
        if (text.isEmpty()) 1 else 1 + (text.length - 1) / MAX_UNITS

    private fun splitsPair(text: String, offset: Int): Boolean =
        offset > 0 && offset < text.length &&
            Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset])

    private fun validate(text: String, window: EditorWindow) {
        require(window.start >= 0 && window.start <= window.end && window.end <= text.length)
        require(window.end - window.start <= MAX_UNITS)
        require(!splitsPair(text, window.start) && !splitsPair(text, window.end))
    }

    fun atOffset(text: String, offset: Int): EditorWindow {
        require(offset in 0..text.length)
        val start = if (splitsPair(text, offset)) offset - 1 else offset
        val available = text.length - start
        var end = start + minOf(MAX_UNITS, available)
        if (splitsPair(text, end)) end--
        return EditorWindow(start, end)
    }

    fun atSection(text: String, section: Int): EditorWindow {
        require(section in 1..sectionCount(text))
        return atOffset(text, (section - 1) * MAX_UNITS)
    }

    fun last(text: String): EditorWindow {
        var start = maxOf(0, text.length - MAX_UNITS)
        if (splitsPair(text, start)) start++
        return EditorWindow(start, text.length)
    }

    fun previous(text: String, window: EditorWindow): EditorWindow? {
        validate(text, window)
        if (window.start == 0) return null
        var start = maxOf(0, window.start - MAX_UNITS)
        if (splitsPair(text, start)) start++
        return EditorWindow(start, window.start)
    }

    fun next(text: String, window: EditorWindow): EditorWindow? {
        validate(text, window)
        if (window.end == text.length) return null
        return atOffset(text, window.end)
    }

    fun replace(text: String, window: EditorWindow, replacement: String): String {
        validate(text, window)
        return text.substring(0, window.start) + replacement + text.substring(window.end)
    }
}
