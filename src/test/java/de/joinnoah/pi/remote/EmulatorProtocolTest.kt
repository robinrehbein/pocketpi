package de.joinnoah.pi.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EmulatorProtocolTest {
    @Test fun inputRejectsUnknownKeysAndUnsupportedKeys() {
        val valid = EmulatorCommand.Input("r", "s", "e", "l", 1, 1, 0, EmulatorInput.Key("Home"))
        assertEquals("emulator.input", encodeEmulatorCommand(valid)["type"]?.toString()?.trim('"'))
        assertThrows(IllegalArgumentException::class.java) { encodeEmulatorCommand(valid.copy(input = EmulatorInput.Key("constructor"))) }
        assertThrows(IllegalArgumentException::class.java) { parseEmulatorResult(Json.parseToJsonElement("""{"kind":"emulator.profiles","profiles":[],"extra":true}""").jsonObject) }
    }
    @Test fun sharedHostFixtures() {
        val fixture = Wire.json.parseToJsonElement(javaClass.getResource("/emulator-v1.json")!!.readText()).jsonObject
        for (group in listOf("valid", "invalid")) for (entry in fixture.getValue(group).jsonArray) {
            val o = entry.jsonObject
            val check = { if ("type" in o) parseEmulatorFrame(o) else parseEmulatorResult(o); Unit }
            if (group == "valid") check() else assertThrows(Exception::class.java) { check() }
        }
    }
}
