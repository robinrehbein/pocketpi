package de.joinnoah.pi.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.Normalizer

/**
 * Set around the Projects entry while it is the left column of the tablet layout.
 * [selectedProjectId] is the project whose sessions are shown beside it; [strip] says a chat is
 * open, so the column is the narrow strip of project avatars.
 */
internal data class ProjectsColumn(val selectedProjectId: String?, val strip: Boolean)

/** The projects column; null on a phone and wherever Projects is a full-screen page. */
internal val LocalProjectsColumn = staticCompositionLocalOf<ProjectsColumn?> { null }

/** Longest the strip's vertical project name grows. */
private val VERTICAL_LABEL_MAX_LENGTH = 240.dp

/**
 * Opens the sessions of [projectId] beside the projects column. Another project replaces the
 * open chat, the same project closes it, and either brings a collapsed session list back.
 */
internal fun openProject(navigator: RemoteNavigator, twoPane: TwoPaneState?, routeId: String, projectId: String) {
    twoPane?.revealList()
    navigator.open(RemoteNavKey.Sessions(routeId, projectId))
}

/**
 * The projects column while a chat is open: Back, one avatar per shared project and the selected
 * project's name, written bottom to top. Tapping another avatar switches the project; the
 * selected one closes the chat.
 */
@Composable
internal fun ProjectStrip(
    key: RemoteNavKey.Projects,
    model: ProjectsViewModel,
    navigator: RemoteNavigator,
    selectedProjectId: String?,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val twoPane = LocalTwoPane.current
    val selectedName =
        state.projects.firstOrNull { it.text("id") == selectedProjectId }?.text("name")
            ?: state.project?.optionalText("name")
    val selectedIndex = state.projects.indexOfFirst { it.text("id") == selectedProjectId }
    val listState = rememberLazyListState()
    // F6 reaches the avatars, and comes back to the one it left.
    val regionChildren = remember { FocusRegionChildren() }
    val region = rememberFocusRegion(FocusRegion.PROJECTS, children = regionChildren)
    // The strip opens with every chat, also after a switch or a restore: keep its avatar in view.
    LaunchedEffect(selectedIndex) {
        if (selectedIndex < 0) return@LaunchedEffect
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
        val fullyVisible =
            item != null && item.offset >= info.viewportStartOffset &&
                item.offset + item.size <= info.viewportEndOffset
        if (!fullyVisible) listState.scrollToItem(selectedIndex)
    }
    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.Start))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FloatingSurface(modifier = Modifier.testTag("projectStripBack"), shape = CircleShape) {
            IconButton(onClick = navigator::back, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.remote_back))
            }
        }
        Spacer(Modifier.height(26.dp))
        CompositionLocalProvider(LocalFocusRegionChildren provides regionChildren) {
            LazyColumn(
                Modifier.weight(1f, fill = false).testTag("projectStripList").then(region),
                state = listState,
                // Each avatar's 2 dp focus margin keeps them 4 dp apart.
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(state.projects, key = { it.text("id") }) { project ->
                    val projectId = project.text("id")
                    ProjectAvatar(
                        name = project.text("name"),
                        projectId = projectId,
                        selected = projectId == selectedProjectId,
                        onClick = { openProject(navigator, twoPane, key.routeId, projectId) },
                    )
                }
            }
        }
        if (!selectedName.isNullOrBlank())
            VerticalLabel(selectedName, Modifier.padding(top = 12.dp).testTag("projectStripLabel"))
    }
}

@Composable
private fun ProjectAvatar(name: String, projectId: String, selected: Boolean, onClick: () -> Unit) {
    val description = stringResource(R.string.remote_project_strip_open, name)
    Box(
        // The keyboard's focus ring takes the outer 2 dp, outside the selected ring.
        Modifier.size(52.dp)
            .clip(CircleShape)
            .focusRing(CircleShape)
            .then(rememberRegionChild())
            .selectable(selected = selected, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag("projectAvatar-$projectId")
            .padding(2.dp)
            // The selected ring leaves a 2 dp gap around the 40 dp avatar.
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                projectInitials(name),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                // The avatar's "Open <name>" says it all; the letters would only repeat it.
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/** [text] on one line, turned to read from bottom to top, and cut short past 240 dp. */
@Composable
private fun VerticalLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier.layout { measurable, constraints ->
                // The text runs along the height, so the height bounds its length.
                val longest = VERTICAL_LABEL_MAX_LENGTH.roundToPx()
                val length = if (constraints.hasBoundedHeight) minOf(constraints.maxHeight, longest) else longest
                val placeable = measurable.measure(Constraints(maxWidth = length))
                layout(placeable.height, placeable.width) {
                    // Turned about its centre, which lands on the centre of the swapped box.
                    placeable.placeWithLayer(
                        x = (placeable.height - placeable.width) / 2,
                        y = (placeable.width - placeable.height) / 2,
                    ) {
                        rotationZ = -90f
                    }
                }
            },
    )
}

/**
 * Up to two letters for a project avatar: the first letters of the first two words, where words
 * are split at anything but letters and digits and at lower-to-upper case changes
 * (`noah-monorepo` is "NM", `PocketPi` "PP"); a single word gives its first two letters
 * (`pocketpi` is "PO"). A blank name gives "?". Accents count as part of their letter, whether
 * the name spells them precomposed or as combining marks.
 */
internal fun projectInitials(name: String): String {
    fun isMark(codePoint: Int): Boolean =
        when (Character.getType(codePoint).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
            else -> false
        }
    val words = mutableListOf<String>()
    val word = StringBuilder()
    // The last letter or digit, so a mark between two letters cannot hide a case change.
    var previous = 0
    fun endWord() {
        if (word.isNotEmpty()) words += word.toString()
        word.clear()
    }
    Normalizer.normalize(name, Normalizer.Form.NFC).codePoints().forEach { codePoint ->
        when {
            isMark(codePoint) -> if (word.isNotEmpty()) word.appendCodePoint(codePoint)
            !Character.isLetterOrDigit(codePoint) -> {
                endWord()
                previous = codePoint
            }
            else -> {
                if (word.isNotEmpty() && Character.isLowerCase(previous) && Character.isUpperCase(codePoint)) endWord()
                word.appendCodePoint(codePoint)
                previous = codePoint
            }
        }
    }
    endWord()
    // The first [count] letters together with the marks that follow them.
    fun String.firstLetters(count: Int): String {
        var end = 0
        var letters = 0
        while (end < length) {
            val codePoint = codePointAt(end)
            if (!isMark(codePoint)) {
                if (letters == count) break
                letters++
            }
            end += Character.charCount(codePoint)
        }
        return substring(0, end)
    }
    val initials =
        when {
            words.size >= 2 -> words[0].firstLetters(1) + words[1].firstLetters(1)
            words.size == 1 -> words[0].firstLetters(2)
            else -> name.trim().firstLetters(2)
        }
    return Normalizer.normalize(initials.uppercase(), Normalizer.Form.NFC).ifBlank { "?" }
}
