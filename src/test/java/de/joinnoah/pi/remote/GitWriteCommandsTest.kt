package de.joinnoah.pi.remote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GitWriteCommandsTest {
    private val session = "019a2f3c-7d41-7b1e-9c55-3e8f1a2b4c6d"
    private val snapshot = "Q2hhbmdlc1NuYXBzaG90MQ"
    private val write = "d3JpdGUtaWQtdG9rZW4tMQ"
    private val fixture by lazy { Wire.json.parseToJsonElement(javaClass.getResource("/git-write-v1.json")!!.readText()).jsonObject }
    private val pushFixture by lazy { Wire.json.parseToJsonElement(javaClass.getResource("/git-push-v1.json")!!.readText()).jsonObject }
    private fun valid(name: String) = fixture.getValue("valid").jsonArray.map { it.jsonObject }.single { it.text("name") == name }.obj("payload")
    private fun rejected(command: GitWriteCommand) = assertTrue(runCatching { encodeGitWriteCommand(command) }.isFailure)
    private fun pushValid(name: String) = pushFixture.getValue("valid").jsonArray.map { it.jsonObject }.single { it.text("name") == name }.obj("payload")

    @Test fun encodesCanonicalHostCommandsExactly() {
        assertEquals(valid("branch-command"), encodeGitWriteCommand(GitWriteCommand.Branch("req-1", session)))
        assertEquals(valid("commit-command-all"), encodeGitWriteCommand(GitWriteCommand.Commit("req-1", session, snapshot, "Fix the login redirect")))
        assertEquals(valid("commit-command-selection"), encodeGitWriteCommand(GitWriteCommand.Commit("req-1", session, snapshot, "Fix the login redirect\n\nUsers now land on the page they came from.\n", listOf("src/app.ts", "docs/readme.md"))))
        assertEquals(valid("push-command"), encodeGitWriteCommand(GitWriteCommand.Push("req-1", session, write)))
        assertEquals(pushValid("branch-for-push"), encodeGitWriteCommand(GitWriteCommand.Branch("req-1", session, forPush = true)))
        assertEquals(pushValid("push-status-command"), encodeGitWriteCommand(GitWriteCommand.PushStatus("req-1", session, write)))
        assertEquals(valid("pull-request-command"), encodeGitWriteCommand(GitWriteCommand.PullRequest("req-1", session, write, "dev", "Fix the login redirect", "Sends users back to the page they came from.", false)))
    }

    @Test fun refusesInvalidOpaqueIdsTokensAndPathSelections() {
        rejected(GitWriteCommand.Branch("bad id", session))
        rejected(GitWriteCommand.Branch("req", ""))
        rejected(GitWriteCommand.Push("req", session, "d3JpdGUtaWQtdG9rZW4tMR"))
        val invalidStatus = pushFixture.array("invalid").single { it.text("name") == "push-status-bad-token" }.obj("payload")
        rejected(GitWriteCommand.PushStatus(invalidStatus.text("requestId"), invalidStatus.text("sessionId"), invalidStatus.text("writeId")))
        rejected(GitWriteCommand.Commit("req", session, snapshot, "Fix", emptyList()))
        rejected(GitWriteCommand.Commit("req", session, snapshot, "Fix", listOf("a", "a")))
        rejected(GitWriteCommand.Commit("req", session, snapshot, "Fix", listOf("x\u0000y")))
        rejected(GitWriteCommand.Commit("req", session, snapshot, "Fix", listOf("é".repeat(2049))))
        rejected(GitWriteCommand.Commit("req", session, snapshot, "Fix", List(1001) { "file$it" }))
    }

    @Test fun validatesUtf8AndTextBoundariesWithoutSplittingSurrogates() {
        assertNotNull(encodeGitWriteCommand(GitWriteCommand.Commit("req", session, snapshot, "é".repeat(128))))
        rejected(GitWriteCommand.Commit("req", session, snapshot, "é".repeat(129)))
        rejected(GitWriteCommand.Commit("req", session, snapshot, "\uFEFF\nbody"))
        rejected(GitWriteCommand.Commit("req", session, snapshot, "Fix\u0000"))
        rejected(GitWriteCommand.Commit("req", session, snapshot, "Fix\n" + "x".repeat(16381)))
        rejected(GitWriteCommand.Commit("req", session, snapshot, "Fix\uD800"))
        rejected(GitWriteCommand.PullRequest("req", session, write, "/bad", "Title", "", false))
        rejected(GitWriteCommand.PullRequest("req", session, write, "dev", "Bad\nTitle", "", false))
        rejected(GitWriteCommand.PullRequest("req", session, write, "dev", "Title", "x".repeat(32769), false))
        rejected(GitWriteCommand.PullRequest("req", session, write, "dev", "Title", "\uDC00", false))
        assertNotNull(encodeGitWriteCommand(GitWriteCommand.PullRequest("req", session, write, "dev", "🚀", "", true)))
    }
}
