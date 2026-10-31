package de.joinnoah.pi.remote

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMessagingServiceTest {
    /**
     * Preferences that lose what was only applied when the process dies: [restarted] keeps what
     * a commit wrote, as the file on disk would.
     */
    private class Preferences(
        private val durable: MutableMap<String, Any?> = mutableMapOf(),
    ) : SharedPreferences {
        private val memory = durable.toMutableMap()
        var commits = 0

        fun restarted() = Preferences(durable.toMutableMap())

        override fun getAll(): Map<String, *> = memory.toMap()

        override fun getString(key: String, defValue: String?) = memory[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?) = memory[key] as? Set<String> ?: defValues

        override fun getInt(key: String, defValue: Int) = memory[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long) = memory[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float) = memory[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean) = memory[key] as? Boolean ?: defValue

        override fun contains(key: String) = key in memory

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor =
            object : SharedPreferences.Editor {
                private val changes = mutableMapOf<String, Any?>()
                private var clear = false

                private fun put(key: String, value: Any?) = apply { changes[key] = value }

                override fun putString(key: String, value: String?) = put(key, value)

                override fun putStringSet(key: String, values: Set<String>?) = put(key, values?.toSet())

                override fun putInt(key: String, value: Int) = put(key, value)

                override fun putLong(key: String, value: Long) = put(key, value)

                override fun putFloat(key: String, value: Float) = put(key, value)

                override fun putBoolean(key: String, value: Boolean) = put(key, value)

                override fun remove(key: String) = put(key, null)

                override fun clear() = apply { clear = true }

                private fun write(target: MutableMap<String, Any?>) {
                    if (clear) target.clear()
                    changes.forEach { (key, value) -> if (value == null) target.remove(key) else target[key] = value }
                }

                override fun commit(): Boolean {
                    commits++
                    write(memory)
                    write(durable)
                    return true
                }

                override fun apply() = write(memory)
            }
    }

    /** Records what a push did, with the app's answers as plain fields. */
    private class Host(
        var routes: Set<String> = setOf("r"),
        var enabled: Boolean = true,
        var canPost: Boolean = true,
        var showing: Pair<String, String>? = null,
        val deliveries: PushDeliveries = PushDeliveries(Preferences()),
    ) : PushHost {
        val log = mutableListOf<String>()
        val wakes = mutableListOf<String>()

        override fun paired(routeId: String) = routeId in routes

        override fun pushEnabled() = enabled

        override fun firstDelivery(eventId: String) = deliveries.first(eventId)

        override fun canPost() = canPost

        override fun isShowing(routeId: String, sessionId: String) = showing == routeId to sessionId

        override fun recordState(payload: PushPayload) {
            log += "state:${payload.eventId}"
        }

        override fun wake(payload: PushPayload) {
            wakes += "${payload.routeId}:${payload.eventId}"
        }

        override fun postAttention(payload: PushPayload) {
            log += "attention:${payload.eventId}"
        }

        override fun postComplete(payload: PushPayload) {
            log += "complete:${payload.eventId}"
        }

        override fun postQuestion(payload: PushPayload) {
            log += "question:${payload.eventId}"
        }
    }

    private val complete = PushPayload("r", "s", "e1", PushEvent.COMPLETE)
    private val question = PushPayload("r", "s", "e2", PushEvent.QUESTION)

    @Test
    fun aPushPostsOnceEvenWhenFcmDeliversItAgainAfterAProcessKill() {
        val preferences = Preferences()
        receivePush(complete, Host(deliveries = PushDeliveries(preferences)))
        val again = Host(deliveries = PushDeliveries(preferences.restarted()))
        receivePush(complete, again)
        assertEquals(emptyList<String>(), again.log)
        receivePush(question, again)
        assertEquals(listOf("state:e2", "question:e2"), again.log)
    }

    @Test
    fun aPushForAPairedHostWithPushOnWakesTheMainConnectionOnceWhateverTheNotificationDoes() {
        val host = Host(showing = "r" to "s")
        receivePush(complete, host)
        assertEquals(listOf("r:e1"), host.wakes)
        // A duplicate delivery wakes nothing again.
        receivePush(complete, host)
        assertEquals(listOf("r:e1"), host.wakes)
        // The wake does not depend on the notification being posted.
        val silent = Host(canPost = false)
        receivePush(question, silent)
        assertEquals(listOf("r:e2"), silent.wakes)
    }

    @Test
    fun noWakeForAnUnpairedHostOrWithPushSwitchedOff() {
        val unpaired = Host(routes = emptySet())
        receivePush(complete, unpaired)
        val off = Host(enabled = false)
        receivePush(complete, off)
        assertEquals(emptyList<String>(), unpaired.wakes + off.wakes)
    }

    @Test
    fun aPushForAnUnpairedHostIsDroppedWithoutBeingRecorded() {
        val host = Host(routes = emptySet())
        receivePush(complete, host)
        assertEquals(emptyList<String>(), host.log)
        // Paired again, the same event still posts.
        host.routes = setOf("r")
        receivePush(complete, host)
        assertEquals(listOf("state:e1", "complete:e1"), host.log)
    }

    @Test
    fun pushSwitchedOffPostsNothingAndStartsNoLookup() {
        val host = Host(enabled = false)
        receivePush(complete, host)
        receivePush(PushPayload("r", "s", "e3", PushEvent.SUBAGENT_DONE), host)
        assertEquals(emptyList<String>(), host.log)
    }

    @Test
    fun theOpenChatSuppressesItsNotificationButStillLearnsTheState() {
        val host = Host(showing = "r" to "s")
        receivePush(complete, host)
        receivePush(PushPayload("r", "s", "e3", PushEvent.SUBAGENT_DONE), host)
        assertEquals(listOf("state:e1"), host.log)
        host.showing = "r" to "other"
        receivePush(question, host)
        assertEquals(listOf("state:e1", "state:e2", "question:e2"), host.log)
    }

    @Test
    fun withoutNotificationPermissionOnlyTheStateIsRecorded() {
        val host = Host(canPost = false)
        receivePush(question, host)
        assertEquals(listOf("state:e2"), host.log)
    }

    @Test
    fun aMissingRouteCacheIsRebuiltFromTheStoreOnce() {
        val preferences = Preferences()
        val cache = PairedRouteCache(preferences)
        var loads = 0
        val load = {
            loads++
            listOf(PairedHost("r", "https://relay.example", "d", "secret", "Mac"))
        }
        assertNull(cache.routes())
        assertEquals(setOf("r"), cache.routes(load))
        assertEquals(setOf("r"), cache.routes(load))
        assertEquals(setOf("r"), PairedRouteCache(preferences.restarted()).routes(load))
        assertEquals(1, loads)
    }

    @Test
    fun pairingAndRemovingAHostKeepTheRouteCacheInStep() {
        val preferences = Preferences()
        val stored = object : PairingStorage {
            var hosts = listOf(PairedHost("r", "https://relay.example", "d", "secret", "Mac"))
            var fail = false

            override fun load() = hosts

            override fun save(hosts: List<PairedHost>) {
                if (fail) error("Disk unavailable")
                this.hosts = hosts
            }
        }
        val cache = PairedRouteCache(preferences)
        val pairings = RouteCachingPairings(stored, cache)
        pairings.load()
        assertEquals(setOf("r"), cache.routes())
        pairings.save(stored.hosts + PairedHost("r2", "https://relay.example", "d2", "secret", "Mini"))
        assertEquals(setOf("r", "r2"), PairedRouteCache(preferences.restarted()).routes())
        pairings.save(stored.hosts.filter { it.routeId == "r2" })
        assertEquals(setOf("r2"), cache.routes())
        // A save that fails leaves no cache to trust, so the next push reads the store again.
        stored.fail = true
        assertTrue(runCatching { pairings.save(emptyList()) }.isFailure)
        assertNull(cache.routes())
        assertEquals(setOf("r2"), cache.routes { stored.load() })
    }

    @Test
    fun deliveriesAreWrittenWithCommit() {
        val preferences = Preferences()
        val deliveries = PushDeliveries(preferences)
        assertTrue(deliveries.first("a"))
        assertFalse(deliveries.first("a"))
        assertEquals(1, preferences.commits)
    }
}
