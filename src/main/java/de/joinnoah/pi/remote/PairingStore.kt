package de.joinnoah.pi.remote

import android.content.Context
import kotlinx.serialization.json.*

data class PairedHost(
    val routeId: String,
    val relay: String,
    val deviceId: String,
    val secret: String,
    val name: String,
) {
    fun json() =
        Wire.objectOf(
            "routeId" to routeId,
            "relay" to relay,
            "deviceId" to deviceId,
            "secret" to secret,
            "name" to name,
        )

    /** Leaves [secret] out, so a host that ends up in a log or a crash report does not leak it. */
    override fun toString() =
        "PairedHost(routeId=$routeId, relay=$relay, deviceId=$deviceId, secret=<redacted>, name=$name)"

    companion object {
        fun from(value: JsonObject) =
            PairedHost(
                value.text("routeId"),
                value.text("relay"),
                value.text("deviceId"),
                value.text("secret"),
                value.text("name"),
            )
    }
}

interface PairingStorage {
    fun load(): List<PairedHost>

    fun save(hosts: List<PairedHost>)

    /**
     * True once after a [load] found the stored pairings unreadable and reset them to none (see
     * [EncryptedFileStore]), so the user is told to pair again.
     */
    fun takeReset(): Boolean = false
}

class PairingStore(
    context: Context,
    name: String = "paired-hosts.enc",
    alias: String = "pi-remote-pairings-v1",
    /** Runs when an unreadable store is reset, before its file is deleted. */
    onReset: () -> Unit = {},
) : PairingStorage {
    private val file = EncryptedFileStore(context, name, alias, onReset)

    override fun load(): List<PairedHost> =
        file
            .read()
            ?.let { bytes ->
                Wire.json.parseToJsonElement(Wire.utf8(bytes)).jsonArray.map {
                    PairedHost.from(it.jsonObject)
                }
            }
            .orEmpty()

    override fun save(hosts: List<PairedHost>) =
        file.write(JsonArray(hosts.map { it.json() }).toString().toByteArray())

    override fun takeReset() = file.takeReset()
}
