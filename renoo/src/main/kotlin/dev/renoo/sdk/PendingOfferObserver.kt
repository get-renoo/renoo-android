package dev.renoo.sdk

import java.util.UUID

/**
 * Called on the **main thread** whenever [Renoo.pendingOffer] changes: a new offer arrived (`200`
 * from `GET /offers`) or the offer went away (`204`, [Renoo.reset], [Renoo.optOut], kill switch),
 * in which case [offer] is `null`. Not called when an observer is added: read [Renoo.pendingOffer]
 * for the current value. The SDK renders nothing; showing the offer is the app's job.
 */
public fun interface PendingOfferObserver {
    public fun onPendingOfferChanged(offer: Map<String, Any?>?)
}

/** Returned by [Renoo.addPendingOfferObserver]; pass it to [Renoo.removePendingOfferObserver]. */
public class PendingOfferObserverToken internal constructor() {
    internal val id: String = UUID.randomUUID().toString()

    override fun equals(other: Any?): Boolean = other is PendingOfferObserverToken && other.id == id

    override fun hashCode(): Int = id.hashCode()
}
