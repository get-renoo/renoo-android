package dev.renoo.sdk

import java.util.UUID

/** Receives every committed event, on the SDK's background thread. Keep it quick. */
public fun interface EventObserver {
    public fun onEvent(event: Event)
}

/** Returned by [Renoo.addEventObserver]; pass it to [Renoo.removeEventObserver]. */
public class EventObserverToken internal constructor() {
    internal val id: String = UUID.randomUUID().toString()

    override fun equals(other: Any?): Boolean = other is EventObserverToken && other.id == id

    override fun hashCode(): Int = id.hashCode()
}
