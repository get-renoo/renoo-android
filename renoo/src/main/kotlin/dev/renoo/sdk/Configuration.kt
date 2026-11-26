package dev.renoo.sdk

/** How much the SDK logs to Logcat (tag `Renoo`). */
public enum class LogLevel {
    NONE,
    ERROR,
    INFO,
    DEBUG,
}

/** Which users the SDK tracks. */
public enum class TrackingScope {
    /** Default. Only users with an active subscription or trial are tracked. */
    SUBSCRIBERS_ONLY,

    /** Track everyone. For development and test apps only. */
    EVERYONE,
}

/**
 * What the SDK asks the client's purchase code to buy. Unused until offer purchases ship.
 *
 * @property accountToken a UUID string the client passes to Play as the obfuscated account ID.
 */
public class PurchaseRequest(
    public val productId: String,
    public val offerId: String?,
    public val accountToken: String,
) {
    override fun toString(): String = "PurchaseRequest(productId=$productId, offerId=$offerId)"
}

/** The only four results a purchase handler may return. */
public enum class PurchaseResult(public val wireValue: String) {
    PURCHASED("purchased"),
    CANCELLED("cancelled"),
    FAILED("failed"),
    PENDING("pending"),
}

/**
 * Client code that completes a discounted purchase. The SDK never buys anything itself.
 * Call [onResult] exactly once, on any thread. Placeholder until offer purchases ship.
 */
public fun interface PurchaseHandler {
    public fun purchase(request: PurchaseRequest, onResult: (PurchaseResult) -> Unit)
}

/**
 * SDK settings, passed once to [Renoo.configure].
 *
 * The session timeout is not a setting: a session ends after more than 30 s in the background.
 *
 * @property automaticScreenTracking capture Activity screen views automatically.
 * @property subscriptionProductIds the app's Play subscription product IDs (optional). With them the
 *   SDK reads their base plans and free trials from Play's catalogue before a purchase, while the
 *   user is still eligible, which makes the inferred `is_trial` / `expires_at` much more reliable.
 *   Read-only and kept on the device; nothing is emitted for non-subscribers. Sanitised: trimmed,
 *   blanks and duplicates dropped, at most [MAX_SUBSCRIPTION_PRODUCT_IDS] IDs of at most
 *   [MAX_PRODUCT_ID_LENGTH] characters. Later this list will come from remote config / the
 *   dashboard's offer catalogue.
 * @property ingestUrl base URL of the Renoo API, e.g. `https://api.renoo.dev` (no path needed; a
 *   trailing slash is fine). The SDK uploads to `POST /events/sdk` and, on start-up, calls
 *   `GET /health-check` and `GET /offers` on the same base. `null` (the default) means capture
 *   only: events stay in the on-disk queue and no network code runs. Must be `https://`; anything
 *   else disables all network calls (an error is logged, capture continues).
 * @property allowInsecureHttp **Debug only, for a local API.** When `true`, an `http://` [ingestUrl]
 *   is accepted. The host app must also allow cleartext traffic to that host (network security
 *   config). Never set it in a release build.
 */
public class Configuration @JvmOverloads constructor(
    public val apiKey: String,
    public val purchaseHandler: PurchaseHandler,
    public val logLevel: LogLevel = LogLevel.ERROR,
    public val trackingScope: TrackingScope = TrackingScope.SUBSCRIBERS_ONLY,
    public val automaticScreenTracking: Boolean = true,
    subscriptionProductIds: List<String> = emptyList(),
    public val ingestUrl: String? = null,
    public val allowInsecureHttp: Boolean = false,
) {
    public val subscriptionProductIds: List<String> = sanitizeProductIds(subscriptionProductIds)

    internal val isValid: Boolean
        get() = apiKey.isNotBlank()

    public companion object {
        public const val MAX_SUBSCRIPTION_PRODUCT_IDS: Int = 20
        public const val MAX_PRODUCT_ID_LENGTH: Int = 150

        /** Never throws: anything odd in the list is dropped. */
        internal fun sanitizeProductIds(ids: List<String>?): List<String> = try {
            // filterIsInstance: a list from Java may hold nulls despite the type.
            (ids.orEmpty() as List<*>)
                .filterIsInstance<String>()
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.length <= MAX_PRODUCT_ID_LENGTH }
                .distinct()
                .take(MAX_SUBSCRIPTION_PRODUCT_IDS)
                .toList()
        } catch (t: Throwable) {
            emptyList()
        }
    }
}
