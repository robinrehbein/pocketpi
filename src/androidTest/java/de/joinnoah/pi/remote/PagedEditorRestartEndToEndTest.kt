package de.joinnoah.pi.remote

import android.content.ContextWrapper
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Private, opt-in production transport and AppStores proof using NavigationTestActivity. */
class PagedEditorRestartEndToEndTest {
    private val compose = createAndroidComposeRule<NavigationTestActivity>()
    private val showNavigation = mutableStateOf(false)
    private lateinit var fixture: JSONObject
    private lateinit var phase: String
    private lateinit var case: String
    private lateinit var nonce: String
    private lateinit var directory: File
    private lateinit var stores: AppStores
    private lateinit var host: PairedHost
    private lateinit var selection: RemoteSelection
    private lateinit var scope: CoroutineScope
    private lateinit var client: OkHttpClient
    private lateinit var transport: PresenceObservingTransport
    private lateinit var repository: DefaultRemoteRepository
    private var releaseCrashBoundary = ""
    private val localRemovalBlocked = AtomicBoolean(false)
    private val removalLatch = CountDownLatch(1)
    private var native = false
    private var hostRestartExpected = false
    private val source = "😀".repeat(262_144)
    private val edited = "é\\\n".repeat(1_024) + "😀".repeat(261_120)
    private val finalValue get() = edited

    @get:Rule val rules = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue("Explicit private restart fixture required", args.getString("pagedRestartExpected") == "true")
            phase = requireNotNull(args.getString("pagedRestartPhase"))
            case = requireNotNull(args.getString("pagedRestartCase"))
            nonce = requireNotNull(args.getString("pagedRestartNonce"))
            require(phase in setOf("A", "B") && case in setOf("draft", "lost-ack", "positive", "release") && nonce.matches(Regex("[a-f0-9]{32}")))
            releaseCrashBoundary = args.getString("pagedReleaseCrashBoundary") ?: ""
            require(releaseCrashBoundary in setOf("", "host-deleted", "ack-before-local"))
            require(releaseCrashBoundary.isEmpty() || case == "release")
            hostRestartExpected = args.getString("hostRestartExpected") == "true"
            require(releaseCrashBoundary.isEmpty() || !hostRestartExpected)
            require(!hostRestartExpected || (phase == "B" && case in setOf("positive", "release")))
            val path = requireNotNull(args.getString("prStatusFixturePath"))
            fixture = JSONObject(readFixture(path).toString(Charsets.UTF_8))
            val relay = fixture.getString("relayUrl").toHttpUrl()
            require(relay.scheme == "https" && relay.host == "10.0.2.2" && relay.encodedPath == "/")
            for ((field, expectedPath) in listOf("statusUrl" to "/fixture-status", "actionUrl" to "/fixture-action")) {
                val url = fixture.getString(field).toHttpUrl()
                require(url.scheme == "https" && url.host == "10.0.2.2" && url.port == relay.port && url.encodedPath == expectedPath && url.query == null && url.fragment == null)
            }
            require(args.getString("prStatusFixtureExpected") == "true" && fixture.getBoolean("prStatusFixture") && fixture.getString("sessionKind") == "actual-pi")
            native = args.getString("prStatusNativeExpected") == "true"
            require(fixture.getBoolean("nativeWorker") == native && fixture.getBoolean("pagedEditorFixture"))
            require(fixture.getString("pagedEditorCase") == if (case == "draft") "max-edited" else "restart-lost-ack")
            require(fixture.optBoolean("pagedEditorPositiveOutcomes") == (case in setOf("positive", "release")))
            require(fixture.optString("pagedReleaseCrashBoundary", "") == releaseCrashBoundary)
            verifyMetadata("Source", source)
            verifyMetadata("Final", finalValue)
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            directory = File(app.noBackupFilesDir, "paged-restart-$nonce")
            check(directory.mkdirs() || directory.isDirectory)
            val isolated = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = directory }
            stores = AppStores(isolated)
            host = PairedHost(fixture.getString("routeId"), fixture.getString("relayUrl"), fixture.getString("deviceId"), fixture.getString("deviceSecret"), "Restart fixture")
            selection = RemoteSelection(host.routeId, fixture.getString("projectId"), fixture.getString("sessionId"))
            val proofFile = File(directory, "phase-a.json")
            if (phase == "A") {
                check(!proofFile.exists() && stores.pairings.load().isEmpty())
                stores.pairings.save(listOf(host))
                assertEquals(listOf(host), stores.pairings.load())
            } else {
                val proof = JSONObject(proofFile.readText())
                assertNotEquals(proof.getInt("pid"), Process.myPid())
                assertEquals(nonce, proof.getString("nonce"))
                assertEquals(case, proof.getString("case"))
                assertEquals(identity(), proof.getString("identity"))
                assertEquals(listOf(host), stores.pairings.load())
                if (hostRestartExpected || releaseCrashBoundary.isNotEmpty()) verifyRestartStoredProof(proof)
            }
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(readFixture(fixture.getString("caCertPath")).inputStream())
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("fixture", cert) }
            val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }.trustManagers.filterIsInstance<X509TrustManager>().single()
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
            client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, manager).callTimeout(3, TimeUnit.SECONDS).build()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            transport = PresenceObservingTransport(SecureRemoteTransport(scope, client), requireNotNull(selection.sessionId), true, false, holdPositiveRelease = phase == "A" && case == "release" && releaseCrashBoundary.isEmpty())
            val storage = if (phase == "A" && releaseCrashBoundary == "ack-before-local") object : PagedEditorStorage {
                override fun load() = stores.pagedEditor.load()
                override fun save(snapshot: PagedJournalSnapshot) {
                    val disk = stores.pagedEditor.load()
                    if (disk.pendingReleases.isNotEmpty() && snapshot.pendingReleases.isEmpty()) {
                        check(transport.positiveOutcomeReleased.get()) { "Removal must follow a correlated real release ACK" }
                        localRemovalBlocked.set(true)
                        removalLatch.await()
                    }
                    stores.pagedEditor.save(snapshot)
                }
            } else stores.pagedEditor
            repository = DefaultRemoteRepository(stores.pairings, stores.drafts, transport, scope, pagedEditorTestGate = true, pagedEditorStorage = storage, positiveOutcomeTestGate = case in setOf("positive", "release"))
            NavigationTestActivity.contentFactory = {
                {
                    if (showNavigation.value) MaterialTheme {
                        RemoteNavigation(repository, PrStatusSettings(), false, {}, RemoteNotification(host.routeId, requireNotNull(selection.sessionId), 1))
                    }
                }
            }
        }
        override fun after() {
            NavigationTestActivity.contentFactory = { {} }
            if (::repository.isInitialized) InstrumentationRegistry.getInstrumentation().runOnMainSync { repository.setForeground(false); repository.disconnect() }
            if (::scope.isInitialized) scope.cancel()
            if (::client.isInitialized) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }).around(compose)

    @Test fun actualOwnerRecoveryAcrossForceStop() {
        if (hostRestartExpected) {
            phaseBHostRestart()
            return
        }
        val initial = status()
        assertTrue(initial.getBoolean("ownerPidVerified"))
        if (native) assertTrue(initial.getBoolean("nativeWorkerVerified"))
        compose.runOnIdle { repository.setForeground(true); repository.setValidatedNetwork("paged-restart-fixture") }
        compose.waitUntil(60_000) { repository.state.value.hostsLoaded && repository.state.value.hosts.any { it.routeId == host.routeId } }
        activate(RemoteSelection(host.routeId))
        compose.waitUntil(60_000) { repository.state.value.projects.any { it.optionalText("id") == selection.projectId } }
        activate(RemoteSelection(host.routeId, selection.projectId))
        compose.waitUntil(60_000) { repository.state.value.sessions.any { it.optionalText("id") == selection.sessionId } }
        compose.runOnIdle { showNavigation.value = true }
        compose.waitUntil(60_000) {
            val s = repository.state.value
            s.selection == selection && s.connected && !s.loading && QUESTION_PAGED_EDITOR_CAPABILITY in s.capabilities && transport.pagedV3Accepted.get() && (!native || transport.presenceAccepted.get())
        }
        if (phase == "A") phaseA() else phaseB()
    }

    private fun verifyRestartStoredProof(proof: JSONObject) {
        val snapshot = persisted()
        val key = PagedJournalKey(host.routeId, requireNotNull(selection.sessionId), proof.getString("questionId"))
        val expectedBinding = StoredPagedOutcomeBinding(host.deviceId, proof.getString("ownerIncarnationId"),
            proof.getInt("finalBytes"), proof.getString("finalSha256"))
        assertEquals(finalValue, snapshot.drafts[key]?.text)
        if (case == "positive") {
            assertTrue(snapshot.pendingReleases.isEmpty())
            val attempt = snapshot.attempts.values.single()
            assertEquals(key, attempt.key)
            assertEquals(proof.getString("attemptId"), attempt.attemptId)
            assertEquals(proof.getString("attemptGrant"), attempt.grantId)
            assertEquals(proof.getString("attemptUpload"), attempt.uploadId)
            assertEquals(proof.getString("attemptCommit"), attempt.commitRequestId)
            assertEquals(proof.getLong("attemptCreatedAt"), attempt.createdAt)
            assertEquals(finalValue, attempt.text)
            assertEquals(expectedBinding, attempt.outcomeBinding)
        } else {
            if (releaseCrashBoundary.isEmpty()) assertTrue(proof.getBoolean("releaseHeld"))
            else assertEquals(releaseCrashBoundary, proof.getString("releaseCrashBoundary"))
            assertEquals(proof.getString("attemptId"), proof.getString("queuedReleaseId"))
            assertTrue(snapshot.attempts.isEmpty())
            val release = snapshot.pendingReleases.values.single()
            assertEquals(proof.getString("attemptId"), release.releaseId)
            assertEquals(key, release.key)
            assertEquals(host.deviceId, release.deviceId)
            assertEquals(expectedBinding.ownerIncarnationId, release.ownerIncarnationId)
            assertEquals(proof.getString("attemptGrant"), release.grantId)
            assertEquals(proof.getString("attemptUpload"), release.uploadId)
            assertEquals(proof.getString("attemptCommit"), release.commitRequestId)
            assertEquals(expectedBinding.byteLength, release.byteLength)
            assertEquals(expectedBinding.sha256, release.sha256)
        }
    }

    private fun phaseBHostRestart() {
        val proof = JSONObject(File(directory, "phase-a.json").readText())
        val id = proof.getString("questionId")
        val initial = status()
        assertTrue(initial.getBoolean("actualHostRestart"))
        assertTrue(initial.getBoolean("originalHostStopped"))
        assertTrue(initial.getBoolean("originalOwnerStopped"))
        assertTrue(initial.getBoolean("ledgerReadable"))
        val oldPid = initial.getInt("originalHostPid")
        val newPid = initial.getInt("newHostPid")
        assertTrue(oldPid > 0 && newPid > 0 && oldPid != newPid)
        assertTrue(initial.getInt("originalOwnerPid") > 0)
        assertEquals(1, initial.getInt("initialAppliedCount"))
        assertEquals(1, initial.getInt("appliedCount"))
        assertEquals(0, initial.getInt("ownerDispatchCalls"))
        compose.runOnIdle { repository.setForeground(true); repository.setValidatedNetwork("paged-restart-fixture") }
        compose.waitUntil(60_000) { repository.state.value.hostsLoaded && repository.state.value.hosts.any { it.routeId == host.routeId } }
        activate(RemoteSelection(host.routeId))
        compose.waitUntil(60_000) {
            val state = repository.state.value
            state.connected && state.selection == RemoteSelection(host.routeId) && state.capabilitiesKnown &&
                QUESTION_PAGED_EDITOR_CAPABILITY in state.capabilities && QUESTION_EDITOR_POSITIVE_OUTCOME_CAPABILITY in state.capabilities &&
                transport.pagedV3Accepted.get()
        }
        compose.waitUntil(60_000) {
            persisted().attempts.isEmpty() && persisted().pendingReleases.isEmpty() &&
                (case == "release" || transport.positiveOutcomeApplied.get() != null) && transport.positiveOutcomeReleased.get() &&
                statusOrNull()?.let { it.optBoolean("ledgerReadable") && it.optInt("appliedCount", -1) == 0 } == true
        }
        if (case == "release") assertEquals(0, transport.positiveOutcomeQueries.get())
        val receipt = if (case == "release") requireNotNull(transport.positiveOutcomeReleaseReceipt.get()) else requireNotNull(transport.positiveOutcomeApplied.get()).obj("receipt")
        assertEquals(receipt, requireNotNull(transport.positiveOutcomeReleaseReceipt.get()))
        assertEquals(proof.getString("attemptCommit"), receipt.text("commitRequestId"))
        assertEquals(proof.getString("attemptUpload"), receipt.text("uploadId"))
        assertEquals(proof.getString("attemptGrant"), receipt.text("grantId"))
        assertEquals(proof.getString("ownerIncarnationId"), receipt.text("ownerIncarnationId"))
        assertEquals(id, receipt.text("questionId"))
        assertEquals(selection.sessionId, receipt.text("sessionId"))
        assertEquals(proof.getLong("finalBytes"), receipt.long("byteLength"))
        assertEquals(proof.getString("finalSha256"), receipt.text("sha256"))
        assertTrue(transport.positiveOutcomeReleaseAttempts.get() >= 1)
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (SystemClock.elapsedRealtime() < deadline) {
            assertTrue(persisted().attempts.isEmpty() && persisted().pendingReleases.isEmpty())
            assertEquals(Triple(0, 0, 0), transport.uploadCounts())
            assertEquals(0, transport.questionAnswerAttempts.get())
            val current = status()
            assertTrue(current.getBoolean("ledgerReadable"))
            assertEquals(0, current.getInt("appliedCount"))
            assertEquals(0, current.getInt("ownerDispatchCalls"))
            SystemClock.sleep(200)
        }
        val final = status()
        val result = JSONObject().put("nonce", nonce).put("case", case).put("pid", Process.myPid()).put("identity", identity())
            .put("questionId", id).put("hostRestart", true).put("oldHostPid", oldPid).put("newHostPid", newPid)
            .put("ownerStopped", initial.getBoolean("originalOwnerStopped")).put("initialAppliedCount", initial.getInt("initialAppliedCount"))
            .put("finalAppliedCount", final.getInt("appliedCount")).put("positiveReceipt", JSONObject(receipt.toString()))
            .put("cleanupVerified", persisted().attempts.isEmpty() && persisted().pendingReleases.isEmpty())
            .put("releaseAccepted", transport.positiveOutcomeReleased.get()).put("outcomeQueries", transport.positiveOutcomeQueries.get())
            .put("releaseAttempts", transport.positiveOutcomeReleaseAttempts.get()).put("answerAttempts", transport.questionAnswerAttempts.get())
            .put("ownerDispatchCalls", final.getInt("ownerDispatchCalls"))
        File(directory, "phase-b.json").outputStream().use { it.write(result.toString().toByteArray()); it.fd.sync() }
        report("B", id)
    }

    private fun phaseA() {
        val command = fixture.getString("editorProbeCommand")
        val probeNonce = fixture.getString("editorProbeNonce")
        val probeName = requireNotNull(commandName(command))
        compose.waitUntil(60_000) { repository.state.value.commands.any { it.name == probeName } && !repository.state.value.commandsLoading }
        compose.onNodeWithTag("composerField").assertIsDisplayed().performTextInput("$command $probeNonce")
        compose.onNodeWithTag("primaryComposerAction").assertIsEnabled().performClick()
        compose.runOnIdle { WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(60_000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
        compose.waitUntil(60_000) { repository.state.value.questions.any { it.optionalText("kind") == "editor" && it.containsKey("editorUploadGrantId") } }
        val question = repository.state.value.questions.single { it.optionalText("kind") == "editor" }
        val id = question.text("id")
        val grant = question.text("editorUploadGrantId")
        loadSource(id)
        compose.onNodeWithTag("questionEditorField").performScrollTo().performTextReplacement("é\\\n".repeat(1_024))
        compose.waitUntil(60_000) { repository.state.value.pagedEditors[id]?.text == edited }
        val key = PagedJournalKey(host.routeId, requireNotNull(selection.sessionId), id)
        var capturedAttempt: StoredPagedAttempt? = null
        if (case == "draft") {
            compose.waitUntil(60_000) { persisted().drafts[key]?.text == finalValue }
            assertTrue(persisted().attempts.isEmpty())
            assertEquals(0, transport.uploadCounts().first)
        } else {
            compose.onNodeWithText(compose.activity.getString(R.string.remote_send)).performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(120_000) {
                statusOrNull()?.let { it.optBoolean("pagedOwnerVerified") && it.optBoolean("pagedCommitDispatched") && it.optBoolean("pagedLostAckInjected") && it.optBoolean("pagedSdkResultVerified") && it.optInt("pagedCommitCount", -1) == 1 } == true &&
                    (if (case in setOf("positive", "release")) statusOrNull()?.let { it.optBoolean("pagedHostAckDropped") && it.optBoolean("pagedPositiveOutcomeVerified") } == true else transport.pagedCommitUnknown(id, grant, fixture.getInt("pagedEditorFinalBytes"), fixture.getString("pagedEditorFinalSha256"))) && persisted().attempts.values.any { it.key == key && it.text == finalValue }
            }
            assertEquals(1, transport.uploadCounts().third)
            capturedAttempt = persisted().attempts.values.single()
            if (case == "release") {
                compose.runOnIdle { repository.disconnect() }
                activate(RemoteSelection(host.routeId))
                activate(RemoteSelection(host.routeId, selection.projectId))
                activate(selection)
                compose.waitUntil(60_000) {
                    val disk = persisted()
                    val boundaryReached = when (releaseCrashBoundary) {
                        "host-deleted" -> statusOrNull()?.let { it.optInt("pagedReleaseDeletedCount") >= 1 && it.optInt("pagedReleaseAckDroppedCount") >= 1 && it.optBoolean("pagedReleaseLedgerEmpty") } == true
                        "ack-before-local" -> localRemovalBlocked.get() && transport.positiveOutcomeReleased.get() && statusOrNull()?.let { it.optInt("pagedReleaseDeletedCount") >= 1 && it.optInt("pagedReleaseAckPassedCount") >= 1 && it.optBoolean("pagedReleaseLedgerEmpty") } == true
                        else -> transport.positiveReleaseHeld.get()
                    }
                    boundaryReached && disk.attempts.isEmpty() && disk.pendingReleases.size == 1
                }
                val queued = persisted().pendingReleases.values.single()
                assertEquals(capturedAttempt.attemptId, queued.releaseId)
                assertEquals(capturedAttempt.commitRequestId, queued.commitRequestId)
                if (releaseCrashBoundary.isEmpty()) {
                    assertFalse(transport.positiveOutcomeReleased.get())
                    assertEquals(0, transport.positiveOutcomeReleaseAttempts.get())
                } else {
                    assertTrue(transport.positiveOutcomeReleaseAttempts.get() >= 1)
                    assertNotNull(transport.positiveOutcomeApplied.get())
                    assertEquals(releaseCrashBoundary == "ack-before-local", transport.positiveOutcomeReleased.get())
                    assertEquals(releaseCrashBoundary == "ack-before-local", localRemovalBlocked.get())
                    assertEquals(capturedAttempt.grantId, queued.grantId)
                    assertEquals(capturedAttempt.uploadId, queued.uploadId)
                    assertEquals(capturedAttempt.outcomeBinding?.deviceId, queued.deviceId)
                    assertEquals(capturedAttempt.outcomeBinding?.ownerIncarnationId, queued.ownerIncarnationId)
                    assertEquals(capturedAttempt.outcomeBinding?.byteLength, queued.byteLength)
                    assertEquals(capturedAttempt.outcomeBinding?.sha256, queued.sha256)
                }
            }
        }
        val snapshot = persisted()
        if (releaseCrashBoundary.isNotEmpty()) {
            val acceptedId = transport.positiveReleaseAcceptedRequestId.get()
            assertTrue(transport.positiveReleaseObservedRequestIds.isNotEmpty())
            if (releaseCrashBoundary == "ack-before-local") assertTrue(acceptedId != null && acceptedId in transport.positiveReleaseObservedRequestIds)
            else assertNull(acceptedId)
        }
        val owner = status()
        assertTrue("Owner must have replied to a real source read", owner.getInt("pagedSourceReadReplyCount") > 0)
        if (case == "draft") {
            assertEquals(0, owner.getInt("pagedCommitCount"))
            assertFalse(owner.getBoolean("pagedSdkResultVerified"))
            if (native) {
                assertTrue(owner.getBoolean("nativeEditorVerified"))
                assertTrue(owner.getBoolean("nativeEditorPending"))
                assertEquals(0, owner.getInt("nativeEditorOriginalCalls"))
            }
        }
        val attempt = capturedAttempt ?: snapshot.attempts.values.singleOrNull()
        if (case in setOf("positive", "release")) {
            assertNotNull(attempt?.outcomeBinding)
            assertEquals(host.deviceId, attempt?.outcomeBinding?.deviceId)
            assertEquals(question.text("editorOwnerIncarnationId"), attempt?.outcomeBinding?.ownerIncarnationId)
            assertTrue(owner.getBoolean("pagedPositiveOutcomeVerified"))
        }
        val proof = JSONObject().put("nonce", nonce).put("case", case).put("pid", Process.myPid()).put("identity", identity())
            .put("questionId", id).put("grantId", grant).put("sourceBytes", source.toByteArray().size).put("sourceSha256", digest(source))
            .put("finalBytes", finalValue.toByteArray().size).put("finalSha256", digest(finalValue))
            .put("attemptId", attempt?.attemptId ?: "")
            .put("attemptGrant", attempt?.grantId ?: "")
            .put("attemptUpload", attempt?.uploadId ?: "")
            .put("attemptCommit", attempt?.commitRequestId ?: "")
            .put("attemptCreatedAt", attempt?.createdAt ?: 0L)
            .put("ownerIncarnationId", attempt?.outcomeBinding?.ownerIncarnationId ?: "")
            .put("releaseHeld", case == "release" && transport.positiveReleaseHeld.get())
            .put("releaseCrashBoundary", releaseCrashBoundary)
            .put("localRemovalBlocked", localRemovalBlocked.get())
            .put("actualReleaseAckSeen", transport.positiveOutcomeReleased.get())
            .put("releaseRequestIds", JSONArray(transport.positiveReleaseObservedRequestIds.sorted()))
            .put("acceptedReleaseRequestId", transport.positiveReleaseAcceptedRequestId.get() ?: "")
            .put("releaseReceipt", transport.positiveOutcomeReleaseReceipt.get()?.let { JSONObject(it.toString()) } ?: JSONObject())
            .put("releaseDeletedCount", owner.optInt("pagedReleaseDeletedCount"))
            .put("releaseAckDroppedCount", owner.optInt("pagedReleaseAckDroppedCount"))
            .put("releaseAckPassedCount", owner.optInt("pagedReleaseAckPassedCount"))
            .put("releaseLedgerEmpty", owner.optBoolean("pagedReleaseLedgerEmpty"))
            .put("queuedReleaseId", snapshot.pendingReleases.values.singleOrNull()?.releaseId ?: "")
            .put("queuedReleaseTuple", snapshot.pendingReleases.values.singleOrNull()?.let { release ->
                JSONObject().put("routeId", release.key.routeId).put("sessionId", release.key.sessionId)
                    .put("questionId", release.key.questionId).put("deviceId", release.deviceId)
                    .put("ownerIncarnationId", release.ownerIncarnationId).put("grantId", release.grantId)
                    .put("uploadId", release.uploadId).put("commitRequestId", release.commitRequestId)
                    .put("byteLength", release.byteLength).put("sha256", release.sha256)
            } ?: JSONObject())
            .put("ownerQuestion", owner.optString("pagedQuestion", ""))
            .put("sourceReadCount", owner.getInt("pagedSourceReadCount"))
            .put("sourceReadReplyCount", owner.getInt("pagedSourceReadReplyCount"))
            .put("readerCount", owner.getInt("pagedReaderCount"))
            .put("beginCount", owner.getInt("pagedBeginCount"))
            .put("chunkCount", owner.getInt("pagedChunkCount"))
            .put("answerCount", owner.getInt("pagedQuestionAnswerCount"))
            .put("commitCount", owner.getInt("pagedCommitCount"))
        File(directory, "phase-a.json").outputStream().use { it.write(proof.toString().toByteArray()); it.fd.sync() }
        report("A", id)
        // The orchestrator must observe this live PID, then force-stop it; no normal teardown.
        while (true) SystemClock.sleep(1_000)
    }

    private fun phaseB() {
        val proof = JSONObject(File(directory, "phase-a.json").readText())
        val id = proof.getString("questionId")
        val key = PagedJournalKey(host.routeId, requireNotNull(selection.sessionId), id)
        val snapshot = persisted()
        assertEquals(finalValue, snapshot.drafts[key]?.text)
        compose.waitUntil(60_000) { transport.acceptedSnapshotQuestions.get() != null && repository.state.value.connected && !repository.state.value.loading }
        val acceptedQuestions = requireNotNull(transport.acceptedSnapshotQuestions.get())
        val phaseBProof = JSONObject().put("nonce", nonce).put("case", case).put("pid", Process.myPid()).put("identity", identity())
            .put("questionId", id).put("snapshotAccepted", true).put("snapshotContainsQuestion", id in acceptedQuestions)
        if (case == "draft") {
            assertTrue(snapshot.attempts.isEmpty())
            compose.waitUntil(15_000) { repository.state.value.questions.any { it.optionalText("id") == id && it.containsKey("editorSource") && it.containsKey("editorUploadGrantId") } }
            assertEquals("Same live owner question required", proof.getString("ownerQuestion"), status().getString("pagedQuestion"))
            val resumedQuestion = repository.state.value.questions.single { it.optionalText("id") == id }
            assertNotEquals("Reconnected reader must have a fresh grant", proof.getString("grantId"), resumedQuestion.text("editorUploadGrantId"))
            phaseBProof.put("resumedGrantId", resumedQuestion.text("editorUploadGrantId"))
            loadSource(id)
            val owner = status()
            assertEquals(proof.getString("ownerQuestion"), owner.getString("pagedQuestion"))
            assertTrue("Fresh source must be read from the owner", owner.getInt("pagedSourceReadCount") > proof.getInt("sourceReadCount"))
            assertTrue("Fresh source replies must come from the owner", owner.getInt("pagedSourceReadReplyCount") > proof.getInt("sourceReadReplyCount"))
            assertEquals(0, owner.getInt("pagedCommitCount"))
            assertFalse(owner.getBoolean("pagedSdkResultVerified"))
            if (native) {
                assertTrue(owner.getBoolean("nativeEditorVerified"))
                assertTrue(owner.getBoolean("nativeEditorPending"))
                assertEquals(0, owner.getInt("nativeEditorOriginalCalls"))
                assertTrue("New Android reader must be authorized", owner.getInt("pagedReaderCount") > proof.getInt("readerCount"))
            }
            compose.waitUntil(60_000) { repository.state.value.pagedEditors[id]?.recoveryDraft == finalValue }
            assertEquals(source, repository.state.value.pagedEditors[id]?.text)
            assertEquals(0, transport.uploadCounts().first)
            phaseBProof.put("beforeRestore", statusCounts(status()))
            compose.onNodeWithText(compose.activity.getString(R.string.remote_paged_editor_restore)).performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(60_000) { repository.state.value.pagedEditors[id]?.text == finalValue }
            assertEquals(0, transport.uploadCounts().first)
            assertEquals(proof.getInt("beginCount"), status().getInt("pagedBeginCount"))
            assertEquals(0, status().getInt("pagedCommitCount"))
            phaseBProof.put("afterRestoreBeforeSend", statusCounts(status()))
            val question = repository.state.value.questions.single { it.optionalText("id") == id }
            compose.onNodeWithText(compose.activity.getString(R.string.remote_send)).performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(120_000) {
                statusOrNull()?.let { it.optBoolean("pagedOwnerVerified") && it.optBoolean("pagedCommitDispatched") && it.optBoolean("pagedAckVerified") && it.optBoolean("pagedSdkResultVerified") && it.optInt("pagedCommitCount", -1) == 1 } == true &&
                    transport.pagedCommitAccepted(id, question.text("editorUploadGrantId"), fixture.getInt("pagedEditorFinalBytes"), fixture.getString("pagedEditorFinalSha256"))
            }
            phaseBProof.put("outcome", statusCounts(status())).put("sdkVerified", status().getBoolean("pagedSdkResultVerified"))
        } else if (case in setOf("positive", "release")) {
            try {
                compose.waitUntil(60_000) { persisted().attempts.isEmpty() && persisted().pendingReleases.isEmpty() && (case == "release" || transport.positiveOutcomeApplied.get() != null) && transport.positiveOutcomeReleased.get() }
            } catch (failure: Throwable) {
                val state = repository.state.value
                val diagnostic = JSONObject().put("queries", transport.positiveOutcomeQueries.get()).put("applied", transport.positiveOutcomeApplied.get() != null)
                    .put("releaseAttempts", transport.positiveOutcomeReleaseAttempts.get()).put("released", transport.positiveOutcomeReleased.get())
                    .put("attempts", persisted().attempts.size).put("connected", state.connected).put("loading", state.loading)
                    .put("storagePending", state.pagedStoragePending).put("storageFailed", state.pagedStorageFailed)
                    .put("positiveCapability", QUESTION_EDITOR_POSITIVE_OUTCOME_CAPABILITY in state.capabilities)
                File(directory, "phase-b-diagnostic.json").writeText(diagnostic.toString())
                throw AssertionError("Positive recovery stalled: $diagnostic", failure)
            }
            if (case == "release") {
                if (releaseCrashBoundary.isEmpty()) assertTrue(proof.getBoolean("releaseHeld"))
                else assertEquals(releaseCrashBoundary, proof.getString("releaseCrashBoundary"))
                assertEquals(proof.getString("attemptId"), proof.getString("queuedReleaseId"))
                assertEquals(0, transport.positiveOutcomeQueries.get())
            }
            val applied = if (case == "release") requireNotNull(transport.positiveOutcomeReleaseReceipt.get()) else requireNotNull(transport.positiveOutcomeApplied.get()).obj("receipt")
            assertEquals(proof.getString("attemptCommit"), applied.text("commitRequestId"))
            assertEquals(proof.getString("attemptUpload"), applied.text("uploadId"))
            assertEquals(proof.getString("attemptGrant"), applied.text("grantId"))
            assertEquals(proof.getString("ownerIncarnationId"), applied.text("ownerIncarnationId"))
            assertEquals(id, applied.text("questionId")); assertEquals(selection.sessionId, applied.text("sessionId"))
            assertEquals(1_048_576L, applied.long("byteLength")); assertEquals(digest(finalValue), applied.text("sha256"))
            assertFalse(id in acceptedQuestions)
            val before = statusCounts(status()); val deadline = SystemClock.elapsedRealtime() + 3_000
            while (SystemClock.elapsedRealtime() < deadline) {
                assertTrue(persisted().attempts.isEmpty())
                assertTrue(persisted().pendingReleases.isEmpty())
                assertEquals(Triple(0, 0, 0), transport.uploadCounts())
                assertEquals(0, transport.questionAnswerAttempts.get())
                val current = statusCounts(status())
                for (field in listOf("beginCount", "chunkCount", "commitCount", "answerCount")) assertEquals(before.getInt(field), current.getInt(field))
                SystemClock.sleep(200)
            }
            if (releaseCrashBoundary.isNotEmpty()) {
                val aIdsJson = proof.getJSONArray("releaseRequestIds")
                val aIds = (0 until aIdsJson.length()).map { aIdsJson.getString(it) }.toSet()
                val bIds = transport.positiveReleaseObservedRequestIds.toSet()
                val acceptedId = transport.positiveReleaseAcceptedRequestId.get()
                assertTrue(aIds.isNotEmpty() && bIds.isNotEmpty())
                assertTrue("Release request IDs must be fresh across processes", aIds.intersect(bIds).isEmpty())
                assertTrue("ACK must match a release actually sent in phase B", acceptedId != null && acceptedId in bIds)
            }
            phaseBProof.put("releaseRequestIds", JSONArray(transport.positiveReleaseObservedRequestIds.sorted()))
                .put("acceptedReleaseRequestId", transport.positiveReleaseAcceptedRequestId.get() ?: "")
                .put("releaseCrashBoundary", releaseCrashBoundary)
                .put("preConnectQueueVerified", releaseCrashBoundary.isNotEmpty())
                .put("queuedReleaseId", proof.optString("queuedReleaseId"))
                .put("positiveReceipt", JSONObject(applied.toString())).put("outcomeQueries", transport.positiveOutcomeQueries.get()).put("releaseAttempts", transport.positiveOutcomeReleaseAttempts.get())
                .put("cleanupVerified", persisted().attempts.isEmpty() && persisted().pendingReleases.isEmpty()).put("releaseAccepted", transport.positiveOutcomeReleased.get()).put("beforeObservation", before).put("afterObservation", statusCounts(status())).put("sdkVerified", status().getBoolean("pagedSdkResultVerified")).put("answerAttempts", transport.questionAnswerAttempts.get())
        } else {
            val attempt = snapshot.attempts.values.single()
            assertEquals(key, attempt.key)
            assertEquals(proof.getString("attemptId"), attempt.attemptId)
            assertEquals(proof.getString("attemptGrant"), attempt.grantId)
            assertEquals(proof.getString("attemptUpload"), attempt.uploadId)
            assertEquals(proof.getString("attemptCommit"), attempt.commitRequestId)
            assertEquals(proof.getLong("attemptCreatedAt"), attempt.createdAt)
            assertEquals(finalValue, attempt.text)
            assertFalse("Accepted current snapshot must omit the question", id in acceptedQuestions)
            phaseBProof.put("attemptId", attempt.attemptId).put("attemptGrant", attempt.grantId)
                .put("attemptUpload", attempt.uploadId).put("attemptCommit", attempt.commitRequestId)
                .put("attemptCreatedAt", attempt.createdAt).put("attemptBytes", attempt.text.toByteArray().size).put("attemptSha256", digest(attempt.text))
                .put("beforeObservation", statusCounts(status()))
            assertEquals(1, status().getInt("pagedCommitCount"))
            assertEquals(0, transport.uploadCounts().first)
            // The settled owner's absent question is not evidence available to the app
            // for clearing the persisted uncertain attempt.
            val deadline = SystemClock.elapsedRealtime() + 3_000
            while (SystemClock.elapsedRealtime() < deadline) {
                assertTrue(repository.state.value.connected && !repository.state.value.loading)
                assertTrue(transport.acceptedSnapshotQuestions.get()?.let { id !in it } == true)
                assertTrue(repository.state.value.questions.none { it.optionalText("id") == id })
                assertEquals(0, transport.questionAnswerAttempts.get())
                assertEquals(attempt, persisted().attempts[attempt.attemptId])
                assertEquals(0, transport.uploadCounts().first)
                assertEquals(0, transport.uploadCounts().second)
                assertEquals(0, transport.uploadCounts().third)
                val owner = status()
                assertEquals(proof.getInt("beginCount"), owner.getInt("pagedBeginCount"))
                assertEquals(proof.getInt("chunkCount"), owner.getInt("pagedChunkCount"))
                assertEquals(proof.getInt("answerCount"), owner.getInt("pagedQuestionAnswerCount"))
                assertEquals(1, owner.getInt("pagedCommitCount"))
                SystemClock.sleep(200)
            }
            phaseBProof.put("afterObservation", statusCounts(status())).put("sdkVerified", status().getBoolean("pagedSdkResultVerified"))
                .put("answerAttempts", transport.questionAnswerAttempts.get())
        }
        File(directory, "phase-b.json").outputStream().use { it.write(phaseBProof.toString().toByteArray()); it.fd.sync() }
        report("B", id)
    }

    private fun loadSource(id: String) {
        compose.onNodeWithText(compose.activity.getString(R.string.remote_paged_editor_load)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(120_000) { repository.state.value.pagedEditors[id]?.text != null }
        assertEquals(source, repository.state.value.pagedEditors[id]?.text)
        verifyMetadata("Source", requireNotNull(repository.state.value.pagedEditors[id]?.text))
    }
    private fun activate(target: RemoteSelection) {
        compose.runOnIdle { scope.launch { repository.activate(target, ActivationMode.USER_OPEN) } }
        compose.waitUntil(60_000) { repository.state.value.selection == target && !repository.state.value.loading && repository.state.value.connected }
    }
    private fun persisted() = PagedEditorStore(object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext) { override fun getNoBackupFilesDir() = directory }).load()
    private fun statusCounts(owner: JSONObject) = JSONObject()
        .put("sourceReadCount", owner.getInt("pagedSourceReadCount"))
        .put("sourceReadReplyCount", owner.getInt("pagedSourceReadReplyCount"))
        .put("readerCount", owner.getInt("pagedReaderCount"))
        .put("beginCount", owner.getInt("pagedBeginCount"))
        .put("chunkCount", owner.getInt("pagedChunkCount"))
        .put("commitCount", owner.getInt("pagedCommitCount"))
        .put("answerCount", owner.getInt("pagedQuestionAnswerCount"))
    private fun status(): JSONObject = client.newCall(Request.Builder().url(fixture.getString("statusUrl")).build()).execute().use { check(it.isSuccessful); JSONObject(requireNotNull(it.body).string()) }
    private fun statusOrNull() = runCatching { status() }.getOrNull()
    private fun identity() = digest(listOf(fixture.getString("routeId"), fixture.getString("deviceId"), fixture.getString("projectId"), fixture.getString("sessionId"), fixture.getString("editorProbeNonce"), native.toString()).joinToString("\u0000"))
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun verifyMetadata(label: String, value: String) {
        assertEquals(fixture.getInt("pagedEditor${label}Bytes"), value.toByteArray(Charsets.UTF_8).size)
        assertEquals(fixture.getString("pagedEditor${label}Sha256"), digest(value))
    }
    private fun report(phase: String, id: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
        putString("pagedRestartProof", "phase=$phase case=$case nonce=$nonce pid=${Process.myPid()} identity=${identity()} question=$id sourceBytes=${source.toByteArray().size} sourceSha256=${digest(source)} finalBytes=${finalValue.toByteArray().size} finalSha256=${digest(finalValue)}")
    })
    private fun readFixture(path: String): ByteArray {
        require(path.matches(Regex("/data/local/tmp/[A-Za-z0-9._/-]+")) && !path.contains(".."))
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path")
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }.also { require(it.isNotEmpty()) }
    }
}
