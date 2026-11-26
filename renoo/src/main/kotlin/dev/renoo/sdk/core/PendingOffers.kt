package dev.renoo.sdk.core

import dev.renoo.sdk.PendingOfferObserver
import dev.renoo.sdk.PendingOfferObserverToken
import org.json.JSONObject
import java.util.Collections

/** Who `GET /offers` asks about. Sent in headers, never in the URL (URLs end up in access logs). */
internal class RequestIdentity(val anonymousId: String, val userId: String?) {
    override fun toString(): String = "RequestIdentity(identified=${userId != null})"
}

/**
 * The pending offer from `GET /offers` and its observers. [current] is readable from any thread;
 * [set] runs on the SDK executor; observers are called on the main thread through [postToMain],
 * in the order the changes happened.
 *
 * The offer is an immutable `Map<String, Any?>` (nested maps and lists are immutable too) made
 * from the response's JSON object: values are `String`, `Boolean`, `Int`/`Long`, `Double`, `null`,
 * `Map<String, Any?>` or `List<Any?>`. Free-form until the offer contract is fixed.
 */
internal class PendingOffers(
    private val logger: SdkLogger,
    private val postToMain: (() -> Unit) -> Unit,
) {
    private val observers = LinkedHashMap<PendingOfferObserverToken, PendingOfferObserver>()

    @Volatile
    var current: Map<String, Any?>? = null
        private set

    /** **SDK executor only.** Notifies observers only when the value changes. */
    fun set(value: Map<String, Any?>?) {
        if (value == current) return
        current = value
        logger.debug { if (value == null) "offers: pending offer cleared" else "offers: new pending offer" }
        postToMain {
            val snapshot = synchronized(observers) { observers.values.toList() }
            for (observer in snapshot) {
                try {
                    observer.onPendingOfferChanged(value)
                } catch (t: Throwable) {
                    logger.error({ "A pending offer observer threw" }, t)
                }
            }
        }
    }

    fun addObserver(observer: PendingOfferObserver): PendingOfferObserverToken {
        val token = PendingOfferObserverToken()
        synchronized(observers) { observers[token] = observer }
        return token
    }

    fun removeObserver(token: PendingOfferObserverToken) {
        synchronized(observers) { observers.remove(token) }
    }

    companion object {
        /** An immutable deep copy of a parsed JSON object (see the class KDoc for the value types). */
        fun freeze(json: JSONObject): Map<String, Any?> {
            @Suppress("UNCHECKED_CAST")
            return freezeValue(JsonValues.fromJsonObject(json)) as Map<String, Any?>
        }

        private fun freezeValue(value: Any?): Any? = when (value) {
            is Map<*, *> -> {
                val copy = LinkedHashMap<String, Any?>(value.size)
                for ((k, v) in value) copy[k.toString()] = freezeValue(v)
                Collections.unmodifiableMap(copy)
            }
            is List<*> -> Collections.unmodifiableList(value.map(::freezeValue))
            else -> value
        }
    }
}
