package de.joinnoah.pi.remote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GitWriteModelsTest {
    private val session = "019a2f3c-7d41-7b1e-9c55-3e8f1a2b4c6d"
    private val fixture by lazy { Wire.json.parseToJsonElement(javaClass.getResource("/git-write-v1.json")!!.readText()).jsonObject }
    private fun results(group: String) = fixture.getValue(group).jsonArray.map { it.jsonObject }.mapNotNull { entry ->
        val payload = entry.obj("payload")
        val data = payload["data"] as? JsonObject
        if (payload.optionalText("type") == "result" && data?.optionalText("kind")?.startsWith("git.") == true) entry.text("name") to data else null
    }

    @Test fun canonicalHostResultsAcceptEverySuccessAndRefusal() {
        val examples = results("valid")
        assertEquals(65, examples.size)
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
}
