package de.joinnoah.pi.remote

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ShareActivity is exported: whatever another app shares is checked here before it can reach a
 * draft, and only the app's own alias intent carries it on to MainActivity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShareIntakeTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** A provider as the share sheet sees it: per-URI types, sizes and grants. */
    private class FakeAccess(
        val types: Map<Uri, String> = emptyMap(),
        val sizes: Map<Uri, Long> = emptyMap(),
        val unreadable: Set<Uri> = emptySet(),
    ) : ShareAccess {
        override fun type(uri: Uri) = types[uri]

        override fun readable(uri: Uri) = uri !in unreadable

        override fun size(uri: Uri) = sizes[uri]

        override fun own(uri: Uri) = uri.authority == "de.robinrehbein.pocketpi.exports"
    }

    private fun uri(name: String) = Uri.parse("content://com.example.provider/$name")

    private fun send(type: String) = Intent(Intent.ACTION_SEND).setType(type)

    private fun sendMultiple(uris: List<Uri>, type: String = "image/*") =
        Intent(Intent.ACTION_SEND_MULTIPLE).setType(type).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))

    @Test
    fun sharedTextGoesIntoTheDraftWithoutFiles() {
        val content = ShareIntake.parse(send("text/plain").putExtra(Intent.EXTRA_TEXT, "  https://example.com/page \n"), FakeAccess())
        assertEquals(SharedContent("https://example.com/page"), content)
        assertNull("other actions are no share", ShareIntake.parse(Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_TEXT, "x"), FakeAccess()))
    }

    @Test
    fun oneImageIsAttachedAsAPhoto() {
        val image = uri("screenshot.png")
        val intent = send("image/png").putExtra(Intent.EXTRA_STREAM, image)
        val content = ShareIntake.parse(intent, FakeAccess(types = mapOf(image to "image/png")))
        assertEquals(SharedContent(files = listOf(SharedFile(image, image = true))), content)
    }

    @Test
    fun severalImagesFromStreamAndClipDataAreAttachedOnce() {
        val images = (1..3).map { uri("image-$it.jpg") }
        val intent = sendMultiple(images)
        // The system copies EXTRA_STREAM into the ClipData; both name the same files.
        intent.clipData = ClipData.newRawUri("", images[0]).apply { images.drop(1).forEach { addItem(ClipData.Item(it)) } }
        val content = ShareIntake.parse(intent, FakeAccess(types = images.associateWith { "image/jpeg" }))
        assertEquals(images.map { SharedFile(it, image = true) }, content?.files)
        assertEquals(0, content?.skipped)
    }

    @Test
    fun aPdfAndATextFileAreAttachedAsFiles() {
        val pdf = uri("report.pdf")
        val log = uri("build.log")
        val content =
            ShareIntake.parse(
                sendMultiple(listOf(pdf, log), "*/*"),
                FakeAccess(types = mapOf(pdf to "application/pdf", log to "text/plain; charset=utf-8")),
            )
        assertEquals(listOf(SharedFile(pdf, image = false), SharedFile(log, image = false)), content?.files)
    }

    @Test
    fun unsupportedTypesAndFileUrisAreSkipped() {
        val zip = uri("archive.zip")
        val svg = uri("drawing.svg")
        val untyped = uri("unknown")
        val file = Uri.parse("file:///sdcard/Download/secret.pdf")
        val content =
            ShareIntake.parse(
                sendMultiple(listOf(zip, svg, untyped, file), "*/*"),
                FakeAccess(types = mapOf(zip to "application/zip", svg to "image/svg+xml", file to "application/pdf")),
            )
        assertEquals(SharedContent(skipped = 4), content)
        assertTrue(checkNotNull(content).empty)
    }

    @Test
    fun oversizedFilesTextAndFilesBeyondTheLimitAreSkipped() {
        val big = uri("video.png")
        val images = (1..6).map { uri("image-$it.png") }
        val types = (images + big).associateWith { "image/png" }
        val content =
            ShareIntake.parse(
                sendMultiple(listOf(big) + images).putExtra(Intent.EXTRA_TEXT, "x".repeat(ShareIntake.MAX_TEXT_BYTES + 1)),
                FakeAccess(types = types, sizes = mapOf(big to AttachmentImportRules.FILE_BYTES + 1L)),
            )
        assertEquals(images.take(ShareIntake.MAX_FILES).map { SharedFile(it, true) }, content?.files)
        assertNull("text beyond the draft's limit is left out", content?.text)
        // The oversized file, the sixth image and the text.
        assertEquals(3, content?.skipped)
    }

    @Test
    fun uriWithoutReadPermissionOrOfPocketPiItselfIsSkipped() {
        val denied = uri("private.png")
        val own = Uri.parse("content://de.robinrehbein.pocketpi.exports/exports/pi-session.html")
        val content =
            ShareIntake.parse(
                sendMultiple(listOf(denied, own)),
                FakeAccess(types = mapOf(denied to "image/png", own to "text/html"), unreadable = setOf(denied)),
            )
        assertEquals(SharedContent(skipped = 2), content)
    }

    @Test
    fun extrasOtherThanTextAndStreamAreIgnored() {
        val image = uri("photo.jpg")
        val plain = send("image/jpeg").putExtra(Intent.EXTRA_STREAM, image).putExtra(Intent.EXTRA_TEXT, "look")
        val loaded =
            Intent(plain)
                .putExtra(RemoteNotifications.EXTRA_ROUTE, "route")
                .putExtra(RemoteNotifications.EXTRA_TARGET, "session")
                .putExtra(Intent.EXTRA_SUBJECT, "subject")
                .putExtra(Intent.EXTRA_HTML_TEXT, "<b>html</b>")
                .putExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID, "session:route:session")
        val access = FakeAccess(types = mapOf(image to "image/jpeg"))
        assertEquals(ShareIntake.parse(plain, access), ShareIntake.parse(loaded, access))
        // A share action sent straight to the exported MainActivity carries nothing in.
        val request = ShareRequest(SharedContent("look", listOf(SharedFile(image, true))), ShareTarget.Session("route", "project", "session"))
        val forged = LaunchIntents.share(context, request).setComponent(ComponentName(context, MainActivity::class.java))
        assertNull(LaunchIntents.share(forged))
        assertNull(LaunchIntents.target(forged))
    }

    @Test
    fun theHandOffToMainActivityCarriesTheGrantAndComesBackUnchanged() {
        val files = listOf(SharedFile(uri("a.png"), true), SharedFile(uri("b.pdf"), false))
        listOf(
                ShareTarget.Session("route", "project", "session"),
                ShareTarget.Session("route", null, "session"),
                ShareTarget.NewSession("route", "project"),
                ShareTarget.Pick,
            )
            .forEach { target ->
                val request = ShareRequest(SharedContent("text", files), target)
                val intent = LaunchIntents.share(context, request)
                assertEquals(LaunchIntents.ALIAS, intent.component?.className)
                assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertTrue("opens in PocketPi's own task", intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
                assertEquals(files.map { it.uri }, (0 until intent.clipData!!.itemCount).map { intent.clipData!!.getItemAt(it).uri })
                assertEquals(request, LaunchIntents.share(intent))
                assertNull("a share opens no session by itself", LaunchIntents.target(intent))
                val saved = Bundle().apply { putShare("pending", request) }
                assertEquals(request, saved.share("pending"))
            }
        val recents = LaunchIntents.share(context, ShareRequest(SharedContent("text"), ShareTarget.Pick))
        assertNull(LaunchIntents.share(recents.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)))
        val badRoute = LaunchIntents.share(context, ShareRequest(SharedContent("text"), ShareTarget.Session("a b", "project", "session")))
        assertNull(LaunchIntents.share(badRoute))
    }

    @Test
    fun onlyAShortcutPocketPiPublishedSkipsThePicker() {
        val snapshot = HomeSnapshot(true, listOf(HomeSession("route", "project", "App", "session", "Fix", "idle")))
        val id = shortcutId("route", "session")
        assertNull(directShareTarget(id, published = emptyList(), snapshot))
        assertNull(directShareTarget("session:a b:c", published = listOf("session:a b:c"), snapshot))
        assertEquals(ShareTarget.Session("route", "project", "session"), directShareTarget(id, listOf(id), snapshot))
        val unknown = shortcutId("route", "other")
        assertEquals(ShareTarget.Session("route", null, "other"), directShareTarget(unknown, listOf(unknown), snapshot))
    }

    @Test
    fun thePickerListsTheOpenSessionFirstThenRecentOnesOfPairedMacs() {
        val host = PairedHost("route", "relay", "device", "secret", "Mac")
        val snapshot =
            HomeSnapshot(
                true,
                listOf(
                    HomeSession("route", "web", "Web", "older", "Older", "idle", updatedAt = 10, openedAt = 100),
                    HomeSession("route", "app", "App", "open", "Stale title", "running"),
                    HomeSession("route", "api", "API", "newer", "Newer", "idle", openedAt = 200),
                    HomeSession("gone", "x", "X", "unpaired", "Unpaired", "idle", openedAt = 300),
                ),
            )
        val state =
            RemoteState(
                hosts = listOf(host),
                hostsLoaded = true,
                selection = RemoteSelection("route", "app", "open"),
                project = buildJsonObject {
                    put("id", "app")
                    put("name", "App")
                },
                projects = listOf(buildJsonObject { put("id", "app"); put("name", "App") }, buildJsonObject { put("id", "docs"); put("name", "Docs") }),
                session = buildJsonObject {
                    put("id", "open")
                    put("title", "Live title")
                },
            )
        val choices = shareChoices(state, snapshot)
        assertEquals(listOf("open", "newer", "older"), choices.sessions.map { it.session.sessionId })
        assertTrue(choices.sessions.first().open)
        assertEquals("Live title", choices.sessions.first().session.title)
        assertNull("one Mac: no host names", choices.sessions.first().hostName)
        assertEquals(listOf("app", "api", "web", "docs"), choices.projects.map { it.projectId })
        assertEquals("Docs", choices.projects.last().name)

        // Nothing open: the last used project comes first.
        val closed = shareChoices(state.copy(selection = RemoteSelection(), project = null, projects = emptyList(), session = null), snapshot, limit = 2)
        assertEquals(listOf("newer", "older"), closed.sessions.map { it.session.sessionId })
        assertEquals("api", closed.projects.first().projectId)
        val two = state.copy(hosts = listOf(host, host.copy(routeId = "gone", name = "Studio")))
        assertEquals("Studio", shareChoices(two, snapshot).sessions.first { it.session.routeId == "gone" }.hostName)
    }

    @Test
    fun aSharedImageAndPdfReachTheDraftOnceTheChatIsConnected() = runTest {
        val repository = NavigationFakeRepository().apply { importsRun = true }
        val chat = RemoteSelection("route", "project", "session")
        repository.state.value = RemoteState(selection = chat, loading = true, draft = "Typed")
        val image = uri("a.png")
        val pdf = uri("b.pdf")
        var delivered: Boolean? = null
        backgroundScope.launch {
            delivered = deliverShare(repository, SharedContent("Shared", listOf(SharedFile(pdf, false), SharedFile(image, true))), chat)
        }
        runCurrent()
        assertEquals("Typed", repository.state.value.draft)
        repository.state.value = repository.state.value.copy(loading = false)
        runCurrent()
        assertEquals("text goes in once the chat is selected", "Typed\n\nShared", repository.state.value.draft)
        assertTrue("files wait for the connection", repository.imports.isEmpty())
        repository.state.value = repository.state.value.copy(connected = true)
        runCurrent()
        assertEquals(listOf(Triple(chat, listOf(image.toString()), true)), repository.imports)
        repository.state.value = repository.state.value.copy(importingAttachments = false)
        runCurrent()
        assertEquals(Triple(chat, listOf(pdf.toString()), false), repository.imports.last())
        repository.state.value = repository.state.value.copy(importingAttachments = false)
        runCurrent()
        assertEquals(true, delivered)
    }

    @Test
    fun theDeliveryStopsWhenTheChatIsLeftAndRespectsTheAttachmentLimit() = runTest {
        val repository = NavigationFakeRepository()
        val chat = RemoteSelection("route", "project", "session")
        val image = SharedFile(uri("a.png"), true)
        assertFalse(deliverShare(repository, SharedContent(files = listOf(image)), chat))

        repository.state.value = RemoteState(selection = chat)
        var delivered: Boolean? = null
        backgroundScope.launch { delivered = deliverShare(repository, SharedContent(files = listOf(image)), chat) }
        runCurrent()
        repository.state.value = RemoteState(selection = chat.copy(sessionId = "other"), connected = true)
        runCurrent()
        assertEquals(false, delivered)
        assertTrue(repository.imports.isEmpty())

        val full = List(4) { LocalAttachment("a".repeat(21) + it, "x.png", "image", "image/png", 1, "0".repeat(64)) }
        repository.state.value = RemoteState(selection = chat, connected = true, attachments = full)
        val images = (1..3).map { SharedFile(uri("i$it.png"), true) }
        assertTrue(deliverShare(repository, SharedContent(files = images), chat))
        assertEquals(listOf(images.first().uri.toString()), repository.imports.single().second)
        assertEquals(listOf(R.string.remote_attachment_limits), repository.errors)
    }

    @Test
    fun theSummaryNamesFilesAndText() {
        val file = SharedFile(uri("a.png"), true)
        assertEquals("1 file", shareSummary(context, SharedContent(files = listOf(file))))
        assertEquals("2 files and text", shareSummary(context, SharedContent("x", listOf(file, file))))
        assertEquals("Text", shareSummary(context, SharedContent("x")))
        assertNotEquals("", shareSummary(context, SharedContent("x")))
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun thePickerSendsToARecentSessionANewSessionOrThePickMode() {
        val content = SharedContent("text", listOf(SharedFile(uri("a.png"), true)), skipped = 1)
        val choices =
            ShareChoices(
                listOf(ShareSessionChoice(HomeSession("route", "app", "App", "open", "Fix login", "idle"), open = true, hostName = null)),
                listOf(ShareProject("route", "app", "App", null), ShareProject("route", "docs", "Docs", null)),
            )
        val chosen = mutableListOf<ShareTarget>()
        var cancelled = 0
        compose.setContent {
            MaterialTheme { SharePicker(ShareStage.Picking(content, choices), { _, target -> chosen += target }) { cancelled++ } }
        }
        compose.onNodeWithText("1 file and text").assertIsDisplayed()
        compose.onNodeWithTag("shareSkipped").assertIsDisplayed()
        compose.onNodeWithText("Open now", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("shareSession:open").performClick()
        compose.onNodeWithText("New session in App").assertIsDisplayed()
        compose.onNodeWithTag("shareChangeProject").performClick()
        compose.onNodeWithTag("shareProject:docs").performClick()
        compose.onNodeWithText("New session in Docs").performClick()
        compose.onNodeWithTag("shareChooseOther").performClick()
        compose.onNodeWithTag("shareCancel").performClick()
        assertEquals(
            listOf(ShareTarget.Session("route", "app", "open"), ShareTarget.NewSession("route", "docs"), ShareTarget.Pick),
            chosen,
        )
        assertEquals(1, cancelled)
    }

    // The navigator's hand-off of shares with files; the text-only ones are in RemoteNavigationTest.

    @Test
    fun aPickedSessionGetsTheFilesOnceConnected() = runTest {
        val repository = NavigationFakeRepository()
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts, RemoteNavKey.Projects("host"), RemoteNavKey.Sessions("host", "project"))
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        val image = SharedFile(uri("a.png"), true)
        navigation.share(ShareRequest(SharedContent(files = listOf(image)), ShareTarget.Pick))
        navigation.open(RemoteNavKey.Chat("host", "project", "chosen"))
        runCurrent()
        assertTrue("files wait for the connection", repository.imports.isEmpty())
        repository.state.value = repository.state.value.copy(connected = true)
        runCurrent()
        assertEquals(listOf(Triple(RemoteSelection("host", "project", "chosen"), listOf(image.uri.toString()), true)), repository.imports)
    }

    @Test
    fun aNewSessionFromThePickerGetsTheFiles() = runTest {
        val repository = NavigationFakeRepository()
        repository.create = { routeId, projectId ->
            RemoteSelection(routeId, projectId, "created").also {
                repository.state.value = repository.state.value.copy(selection = it, connected = true)
            }
        }
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts, RemoteNavKey.Projects("host"))
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        val pdf = SharedFile(uri("b.pdf"), false)
        navigation.share(ShareRequest(SharedContent(files = listOf(pdf)), ShareTarget.NewSession("host", "project")))
        runCurrent()
        assertEquals(RemoteNavKey.Chat("host", "project", "created"), stack.last())
        assertEquals(listOf(Triple(RemoteSelection("host", "project", "created"), listOf(pdf.uri.toString()), false)), repository.imports)
    }

    @Test
    fun aNavigationBeforeTheChatConnectedDropsTheFilesAndSaysSo() = runTest {
        val repository = NavigationFakeRepository()
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        val image = SharedFile(uri("a.png"), true)
        navigation.share(ShareRequest(SharedContent("Shared", listOf(image)), ShareTarget.Session("host", "project", "session")))
        runCurrent()
        assertEquals(RemoteNavKey.Chat("host", "project", "session"), stack.last())
        assertEquals("Shared", repository.state.value.draft)
        navigation.open(RemoteNavKey.Sessions("host", "project"))
        runCurrent()
        repository.state.value = repository.state.value.copy(connected = true)
        runCurrent()
        assertTrue(repository.imports.isEmpty())
        assertEquals(listOf(R.string.remote_share_dropped), repository.errors)
    }
}
