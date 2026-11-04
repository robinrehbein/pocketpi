package de.joinnoah.pi.remote

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.ScalingLazyListItemScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import kotlinx.coroutines.launch

internal class WatchListScope(private val delegate: ScalingLazyListScope, val context: Context) {
    fun item(key: Any? = null, content: @Composable ScalingLazyListItemScope.() -> Unit) = delegate.item(key, content)
    fun <T> items(values: List<T>, key: ((T) -> Any)? = null, content: @Composable ScalingLazyListItemScope.(T) -> Unit) {
        delegate.items(values.size, key?.let { valueKey -> { index -> valueKey(values[index]) } }) { index -> content(values[index]) }
    }
}

@Composable
internal fun WatchList(title: String, identity: Any, canGoBack: Boolean, back: () -> Unit, content: WatchListScope.() -> Unit) {
    val context = LocalContext.current
    key(identity) {
        val state = rememberScalingLazyListState()
        val focus = remember { FocusRequester() }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) { focus.requestFocus() }
        SwipeToDismissBox(
            onDismissed = back,
            hasBackground = canGoBack,
            state = androidx.wear.compose.foundation.rememberSwipeToDismissBoxState(),
        ) { background ->
            if (!background) {
                Scaffold(
                    timeText = { TimeText(modifier = Modifier.scrollAway(state)) },
                    positionIndicator = { PositionIndicator(state) },
                ) {
                    ScalingLazyColumn(
                        state = state,
                        modifier = Modifier.fillMaxSize().testTag("watchList")
                            .onRotaryScrollEvent { event ->
                                scope.launch { state.scrollBy(event.verticalScrollPixels) }
                                true
                            }
                            .focusRequester(focus).focusable(),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 38.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.title3) }
                        with(WatchListScope(this, context)) {
                            content()
                            if (canGoBack) action(R.string.wear_back, action = back)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun WatchAction(label: String, action: () -> Unit, enabled: Boolean = true, secondary: String? = null, selected: Boolean = false) {
    Chip(
        onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        colors = if (selected) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
        label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = secondary?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
    )
}

internal fun WatchListScope.action(resource: Int, enabled: Boolean = true, action: () -> Unit) {
    item { WatchAction(stringResource(resource), action, enabled) }
}
internal fun WatchListScope.paragraphs(text: String) {
    items(watchTextBlocks(text)) { Text(it, Modifier.fillMaxWidth(), style = MaterialTheme.typography.body2) }
}
internal fun WatchListScope.notice(resource: Int) { paragraphs(context.getString(resource)) }
internal fun WatchListScope.loading() { item { CircularProgressIndicator(Modifier.size(24.dp)) } }

@Composable
internal fun WatchEditor(value: String, changed: (String) -> Unit, label: String, enabled: Boolean = true, limit: Int = 16384, dictationError: () -> Unit, tooLong: () -> Unit = {}) {
    val focus = LocalFocusManager.current
    val latestValue by rememberUpdatedState(value)
    val latestChanged by rememberUpdatedState(changed)
    var dictatedFrom by remember { mutableStateOf<String?>(null) }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val before = dictatedFrom
        dictatedFrom = null
        // Never overwrite edits made while Android's speech UI was open, and never send automatically.
        if (result.resultCode == Activity.RESULT_OK && before != null && latestValue == before) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                val text = watchInputText(listOf(before, it).filter(String::isNotBlank).joinToString(" "), limit)
                if (text.toByteArray(Charsets.UTF_8).size <= 128 * 1024) latestChanged(text) else tooLong()
            }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicTextField(
            value = value, onValueChange = {
                val text = watchInputText(it, limit)
                if (text.toByteArray(Charsets.UTF_8).size <= 128 * 1024) changed(text) else tooLong()
            }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 112.dp)
                .verticalScroll(rememberScrollState())
                .background(MaterialTheme.colors.surface, RoundedCornerShape(16.dp)).padding(12.dp)
                .semantics { contentDescription = label },
            textStyle = MaterialTheme.typography.body2.copy(color = MaterialTheme.colors.onSurface),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            decorationBox = { field ->
                Column { if (value.isEmpty()) Text(label, style = MaterialTheme.typography.caption1); field() }
            },
        )
        WatchAction(stringResource(R.string.wear_dictate), {
            dictatedFrom = latestValue
            try {
                voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
            } catch (_: ActivityNotFoundException) { dictatedFrom = null; dictationError() }
        }, enabled && dictatedFrom == null)
    }
}

/** Used by readers: page size and block height remain bounded even for long tool output. */
internal fun WatchListScope.reader(text: String, page: Int, changePage: (Int) -> Unit, mono: Boolean = false) {
    val blocks = watchTextBlocks(text)
    val safe = page.coerceIn(0, watchPageCount(blocks.size) - 1)
    items(watchPage(blocks, safe)) {
        Text(it, Modifier.fillMaxWidth(), style = MaterialTheme.typography.body2.copy(fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default))
    }
    pager(safe, blocks.size, changePage)
}
internal fun WatchListScope.pager(page: Int, size: Int, changePage: (Int) -> Unit) {
    val count = watchPageCount(size)
    if (count > 1) {
        item { Text(stringResource(R.string.wear_page, page + 1, count)) }
        if (page > 0) action(R.string.wear_previous) { changePage(page - 1) }
        if (page < count - 1) action(R.string.wear_next) { changePage(page + 1) }
    }
}
