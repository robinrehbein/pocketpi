package de.joinnoah.pi.remote

internal const val FILE_TILE_PREVIEW_CHARS = 2048
internal const val MAX_CACHED_FILE_PREVIEWS = 24
internal const val MAX_FILE_PEEK_NAMES = 8

data class FileTilePreview(
    val loading: Boolean = true,
    val content: String? = null,
    val binary: Boolean = false,
    val tooLarge: Boolean = false,
    val failure: FilesFailure? = null,
)

data class FilesPeek(
    val path: String,
    val type: FileEntryType,
    val loading: Boolean = true,
    val listing: FileListing? = null,
    val failure: FilesFailure? = null,
)
