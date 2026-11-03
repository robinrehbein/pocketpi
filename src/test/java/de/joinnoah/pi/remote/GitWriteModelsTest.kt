package de.joinnoah.pi.remote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GitWriteModelsTest {
    private val session = "019a2f3c-7d41-7b1e-9c55-3e8f1a2b4c6d"
    private val fixture by lazy { Wire.json.parseToJsonElement(javaClass.getResource("/git-write-v1.json")!!.readText()).jsonObject }
    private val pushFixture by lazy { Wire.json.parseToJsonElement(javaClass.getResource("/git-push-v1.json")!!.readText()).jsonObject }
    private fun results(group: String) = fixture.getValue(group).jsonArray.map { it.jsonObject }.mapNotNull { entry ->
        val payload = entry.obj("payload")
        val data = payload["data"] as? JsonObject
        if (payload.optionalText("type") == "result" && data?.optionalText("kind")?.startsWith("git.") == true) entry.text("name") to data else null
    }

    @Test fun pullRequestStatusRequiresIdentityOnlyForObservedPullRequests() {
        val fixture = Wire.json.parseToJsonElement(javaClass.getResource("/git-pull-request-v1.json")!!.readText()).jsonObject
        fun results(group: String) = fixture.array(group).map { it.obj("payload") }.filter { it.optionalText("type") == "result" }.map { it.obj("data") }
        val parsed = results("valid").map { parseGitWriteResult(it, session) }
        val statuses = parsed.filterIsInstance<GitWriteResult.PullRequestStatus>()
        assertEquals(GitPullRequestStatus.entries.toSet(), statuses.map { it.state }.toSet())
        assertEquals(GitPullRequestIdentity(12, "https://github.com/example/repo/pull/12", "dev"), statuses.single { it.state == GitPullRequestStatus.FOUND }.pullRequest)
        assertNull(statuses.single { it.state == GitPullRequestStatus.ABSENT }.pullRequest)
        results("invalid").forEach { assertTrue(it.toString(), runCatching { parseGitWriteResult(it, session) }.isFailure) }
    }

    @Test fun canonicalHostResultsAcceptEverySuccessAndRefusal() {
        val examples = results("valid")
        assertEquals(67, examples.size)
        examples.forEach { (name, data) -> assertNotNull(name, parseGitWriteResult(data, session)) }
        val branch = parseGitWriteResult(examples.single { it.first == "branch-available" }.second, session)
        assertTrue(branch is GitWriteResult.Branch.Available)
    }

    @Test fun canonicalMalformedHostResultsAreRejected() {
        val examples = results("invalid")
        assertTrue(examples.size >= 40)
        examples.forEach { (name, data) -> assertTrue(name, runCatching { parseGitWriteResult(data, session) }.isFailure) }
    }

    @Test fun sessionMismatchAndUnknownKindNeverProduceAResult() {
        val data = results("valid").first().second
        assertTrue(runCatching { parseGitWriteResult(data, "other") }.isFailure)
        assertTrue(runCatching { parseGitWriteResult(JsonObject(data + ("kind" to JsonPrimitive("git.unknown"))), session) }.isFailure)
    }

    @Test fun rejectsNonCanonicalTokenAndInvalidUtf8Boundaries() {
        val data = results("valid").single { it.first == "branch-available" }.second
        assertTrue(runCatching { parseGitWriteResult(JsonObject(data + ("writeId" to JsonPrimitive("d3JpdGUtaWQtdG9rZW4tMR"))), session) }.isFailure)
        assertTrue(runCatching { parseGitWriteResult(JsonObject(data + ("suggestedTitle" to JsonPrimitive("é".repeat(129)))), session) }.isFailure)
        assertTrue(runCatching { parseGitWriteResult(JsonObject(data + ("suggestedTitle" to JsonPrimitive("\uD800"))), session) }.isFailure)
    }

    @Test fun pushPreparationHeadAndCurrentRemoteStatusAreStrict() {
        val data = results("valid").single { it.first == "branch-available" }.second
        fun pushResults(group: String) = pushFixture.array(group).map { it.obj("payload") }.filter { it.optionalText("type") == "result" }.map { it.obj("data") }
        val parsed = pushResults("valid").map { parseGitWriteResult(it, session) }
        assertEquals(listOf("a".repeat(40), "b".repeat(64)), parsed.filterIsInstance<GitWriteResult.Branch.Available>().map { it.head })
        assertNull((parseGitWriteResult(data, session) as GitWriteResult.Branch.Available).head)
        assertEquals(setOf(GitPushStatus.MATCHES, GitPushStatus.ABSENT, GitPushStatus.DIFFERENT, GitPushStatus.UNAVAILABLE), parsed.filterIsInstance<GitWriteResult.PushStatus>().map { it.state }.toSet())
        pushResults("invalid").forEach { assertTrue(runCatching { parseGitWriteResult(it, session) }.isFailure) }
    }
}
