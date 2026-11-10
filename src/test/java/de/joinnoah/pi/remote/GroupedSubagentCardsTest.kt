package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonArray
import org.junit.Assert.*
import org.junit.Test

class GroupedSubagentCardsTest {
    private fun call(id: String, name: String = "Agent") = Wire.objectOf(
        "type" to "toolCall", "id" to id, "name" to name, "arguments" to "{}",
    )

    private fun batch(id: String, vararg calls: String) = Wire.objectOf(
        "id" to id, "role" to "assistant", "text" to "",
        "parts" to JsonArray(calls.map { call(it) }),
    )

    private fun output(id: String, mode: String = "single") = Wire.objectOf(
        "id" to "output-$id", "role" to "tool", "toolName" to "Agent",
        "toolCallId" to id, "text" to "Result $id", "state" to "complete",
        "subagentProgress" to Wire.objectOf(
            "mode" to mode, "agents" to JsonArray(listOf(Wire.objectOf(
                "agent" to id, "state" to "running", "instanceId" to "instance-$id",
                "spawnToolCallId" to id,
            ))),
        ),
    )

    @Test fun sameBatchRetainsExactSourcesAndAllOutputs() {
        val messages = listOf(batch("batch", "a", "b"), output("a"), output("b"))
        val grouped = conversationItems(messages).single() as ConversationItem.Subagent
        assertEquals("batch-agent-batch", grouped.id)
        assertEquals(listOf("a", "b"), grouped.agents.map { it.name })
        assertEquals(listOf("Result a", "Result b"), grouped.originalCards().map { it.output })
        for (index in grouped.agents.indices) {
            val (source, local) = grouped.rowSource(index)
            assertSame(grouped.constituents[index], source)
            assertEquals(0, local)
            assertEquals(grouped.agents[index].spawnToolCallId, source.toolCallId)
            assertEquals("instance-${source.toolCallId}", source.agents[local].instanceId)
        }
        assertEquals(listOf("a", "b"), ConversationPresenter().present(messages, false).subagents.map { it.toolCallId })
    }

    @Test fun lateFirstProgressPreservesBatchIdentityAndExactSources() {
        val assistant = batch("batch", "a", "b", "c")
        val presenter = ConversationPresenter()
        val singleton = presenter.present(listOf(assistant, output("b")), true)
            .items.filterIsInstance<ConversationItem.Subagent>().single()
        val before = presenter.present(listOf(assistant, output("b"), output("c")), true)
            .items.filterIsInstance<ConversationItem.Subagent>().single()
        assertEquals(singleton.id, before.id)
        assertEquals(listOf("a", "c"), presenter.present(listOf(assistant, output("b")), true)
            .items.filterIsInstance<ConversationItem.Activity>().map { it.toolCallId })
        val after = presenter.present(listOf(assistant, output("b"), output("c"), output("a")), true)
            .items.filterIsInstance<ConversationItem.Subagent>().single()
        assertEquals(before.id, after.id)
        assertEquals(listOf("a", "b", "c"), after.agents.map { it.name })
        assertEquals(listOf("a", "b", "c"), after.agents.indices.map { after.rowSource(it).first.toolCallId })
    }

    @Test fun separateAssistantMessagesNeverGroup() {
        val items = conversationItems(listOf(batch("one", "a"), batch("two", "b"), output("a"), output("b")))
        assertEquals(2, items.size)
        val cards = items.filterIsInstance<ConversationItem.Subagent>()
        assertNotEquals(cards[0].id, cards[1].id)
        assertTrue(cards.all { it.agents.size == 1 && it.originalCards().size == 1 })
    }

    @Test fun chainAndParallelCardsKeepTheirSemanticsAndActivitiesStayInPlace() {
        val assistant = Wire.objectOf(
            "id" to "batch", "role" to "assistant", "text" to "",
            "parts" to JsonArray(listOf(call("a"), call("read", "read"), call("chain"), call("b"), call("parallel"))),
        )
        val items = conversationItems(listOf(assistant, output("a"), output("b"), output("chain", "chain"), output("parallel", "parallel")))
        assertEquals(4, items.size)
        assertEquals("read", (items[1] as ConversationItem.Activity).toolCallId)
        val chain = items[2] as ConversationItem.Subagent
        assertEquals("chain", chain.mode)
        assertTrue(chain.constituents.isEmpty())
        assertEquals(chain to 0, chain.rowSource(0))
        assertEquals("parallel", (items[3] as ConversationItem.Subagent).mode)
    }
}
