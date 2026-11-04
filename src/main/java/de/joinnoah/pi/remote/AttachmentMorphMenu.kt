package de.joinnoah.pi.remote

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** A fixed-size composer control whose painted circle becomes the attachment panel. */
@Composable
internal fun AttachmentMorphMenu(
    enabled: Boolean,
    onPickPhotos: () -> Unit,
    onPickFiles: () -> Unit,
    onTakePhoto: (() -> Unit)? = null,
    onPaste: (() -> Unit)? = null,
) {
    val state = rememberMorphMenuState()
    val latestEnabled by rememberUpdatedState(enabled)
    val photos by rememberUpdatedState(onPickPhotos)
    val files by rememberUpdatedState(onPickFiles)
    val camera by rememberUpdatedState(onTakePhoto)
    val paste by rememberUpdatedState(onPaste)
    SideEffect { if (!enabled) state.dismiss() }
    DisposableEffect(state) { onDispose { state.dismiss() } }
    val menuDescription = stringResource(
        if (state.expanded) R.string.remote_menu_expanded else R.string.remote_menu_collapsed,
    )
    // Closing retains the popup for its animation, but its actions must not fire again.
    fun choose(action: (() -> Unit)?) {
        if (!latestEnabled || !state.contentInteractive || action == null) return
        state.dismiss()
        action()
    }
    // Popup contributes a layout node: keep it inside the same slot as the anchor so the
    // parent's spacedBy arrangement never adds a gap when the popup appears.
    Box(Modifier.size(48.dp).testTag("attachmentMenuSlot")) {
        ShortcutTooltip(stringResource(R.string.remote_add_attachment)) {
            IconButton(
                onClick = { if (latestEnabled) state.expanded = !state.expanded },
                enabled = enabled,
                modifier = Modifier.size(48.dp).testTag("attachmentMenuAnchor")
                    .semantics { stateDescription = menuDescription }
                    .then(if (state.anchorHidden) Modifier.clearAndSetSemantics {} else Modifier),
            ) {
                FloatingSurface(
                    Modifier.size(32.dp).testTag("attachmentMenuSurface").morphMenuAnchor(state),
                    shape = CircleShape,
                    contentColor = LocalContentColor.current,
                    blur = false,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Add, stringResource(R.string.remote_add_attachment))
                    }
                }
            }
        }
        val rotation by animateFloatAsState(if (state.expanded) 45f else 0f, label = "attachmentClose")
        MorphingMenu(
            state = state,
            direction = MorphMenuDirection.UpFromStart,
            modifier = Modifier.testTag("attachmentMenuPanel"),
            panelWidth = null,
            controls = {
                IconButton(
                    onClick = { state.dismiss() },
                    modifier = Modifier.size(48.dp).semantics { stateDescription = menuDescription },
                ) {
                    Icon(
                        Icons.Default.Add,
                        stringResource(R.string.remote_attachment_menu_close),
                        Modifier.rotate(rotation),
                    )
                }
            },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.remote_pick_photos)) },
                leadingIcon = { Icon(Icons.Default.PhotoLibrary, null) },
                onClick = { choose(photos) },
                enabled = state.contentInteractive,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.remote_pick_files)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                onClick = { choose(files) },
                enabled = state.contentInteractive,
            )
            if (onTakePhoto != null) DropdownMenuItem(
                text = { Text(stringResource(R.string.remote_take_photo)) },
                leadingIcon = { Icon(Icons.Default.PhotoCamera, null) },
                onClick = { choose(camera) },
                enabled = state.contentInteractive,
                modifier = Modifier.testTag("takePhoto"),
            )
            if (onPaste != null) DropdownMenuItem(
                text = { Text(stringResource(R.string.remote_drop_paste_image)) },
                leadingIcon = { Icon(Icons.Default.ContentPaste, null) },
                onClick = { choose(paste) },
                enabled = state.contentInteractive,
                modifier = Modifier.testTag("pasteAttachment"),
            )
        }
    }
}
