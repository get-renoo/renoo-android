# Renoo Android SDK

Captures in-app behaviour for subscription apps with **zero runtime permissions**: app opens, sessions, screens, subscription state, notification status and device context. It sends events to the Renoo API and fetches the pending retention offer for the current user. The iOS twin is [renoo-ios](https://github.com/get-renoo/renoo-ios): same events, same API shape.

Licensed under the Apache License 2.0 ([LICENSE](LICENSE)). Versions follow [Semantic Versioning](https://semver.org/); see [CHANGELOG.md](CHANGELOG.md). Version 0.1.0 emits events with `schema_version` 1.

## Requirements

| | |
| --- | --- |
| Android | minSdk 24 (Android 7.0), compileSdk 36 |
| Language | Kotlin or Java (Java 17 bytecode) |
| Dependencies | Kotlin stdlib and Google Play Billing Library 8.3.0 |

## Install

From Maven Central (`mavenCentral()` is in the default repositories of new Android projects):

```kotlin
dependencies {
    implementation("dev.renoo:renoo-android:0.1.0")
}
```

Minified release builds need no extra configuration: the SDK ships its own R8 rules.

## Usage

Configure once, in `Application.onCreate()` (only the main process is tracked; other processes are ignored):

```kotlin
import dev.renoo.sdk.*

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Renoo.configure(
            this,
            Configuration(
                apiKey = "pk_live_…",                   // your app's public SDK key
                purchaseHandler = MyPurchaseHandler(),   // completes discounted purchases in your billing code
                ingestUrl = "https://api.renoo.dev",     // base URL; null = capture locally, send nothing
                // Optional: your Play subscription product IDs (better trial and expiry hints).
                subscriptionProductIds = listOf("premium_monthly", "premium_annual"),
            ),
        )
    }
}
```

| Call | When |
| --- | --- |
| `Renoo.identify(userId)` | After every login, and at launch if the user is already logged in |
| `Renoo.reset()` | On logout. Clears the user ID and starts a new anonymous ID, so the next person on the device is not merged with the last one |
| `Renoo.optOut()` / `Renoo.optIn()` | When the user withdraws or gives consent. Opt-out stops tracking and clears the queue, user ID, cached state and pending offer |
| `Renoo.anonymousId` | Read-only; the random ID used before (and alongside) your user ID. `null` before `configure` |
| `Renoo.pendingOffer` | Read-only; the offer the backend has for this user, as an immutable `Map<String, Any?>`, or `null` |
| `Renoo.addPendingOfferObserver { offer -> }` | Called on the main thread when the pending offer changes. Keep the token to remove it with `removePendingOfferObserver` |
| `Renoo.trackScreen("Checkout")` | Screens that aren't activities: Fragments, Compose destinations. Activities are captured automatically |

You must call `identify(userId)` after login and `reset()` on logout. If you also use RevenueCat, pass the same user ID to both.

The SDK does not render offers yet; read `pendingOffer` or observe it.

`Configuration` also takes `logLevel` (default `ERROR`; `DEBUG` logs every event), `automaticScreenTracking` (default `true`) and `allowInsecureHttp` (debug only, for a local API over `http://`; the app must also allow cleartext to that host).

## What the SDK does on the network

Only when `ingestUrl` is set. All calls run on the SDK's background thread, use `HttpURLConnection`, send `Authorization: Bearer <apiKey>` and require HTTPS.

| Call | When | Result |
| --- | --- | --- |
| `GET /health-check` | Every start-up | `401` turns the SDK off until the next start-up: capture stops, the queue is deleted, nothing is sent. Anything else: carry on |
| `GET /offers` | Start-up and return to the foreground, at most every 6 hours | `200` stores the pending offer; `204` clears it; `401` as above; errors change nothing and retry at the next trigger. Identity goes in the `X-Renoo-Anonymous-Id` and `X-Renoo-User-Id` headers, never in the URL |
| `POST /events/sdk` | When the app goes to the background, at 100 queued events, at a cold start with queued events, and every 5 minutes in the foreground (on cellular only from 100 events) | Up to 500 events per request, gzipped NDJSON, with `X-Renoo-Batch-Id` (reused on retries) and `X-Renoo-Sent-At` (device clock at each attempt). Failures back off from 30 s to 1 h; events stay on disk until the server accepts them. `401` turns the SDK off as above |

## What is captured

| Event | When |
| --- | --- |
| `install` | First launch after an install |
| `app_open` | Process start (`cold`) or return from the background (`warm`) |
| `app_background` | Every move to the background |
| `session_start` / `session_end` | A session ends after more than 30 s in the background. `session_start` carries the context below |
| `unclean_exit` | Cold start after a run that ended in the foreground (crash proxy) |
| `screen_view` | An activity, or a `trackScreen` call |
| `subscription_state` | At each app open and when Play's purchase screen closes |
| `transaction` | A new Play purchase is seen |
| `push_permission_changed` | Notification permission differs from last time |
| `identify` | Once the user is tracked, on each `identify(userId)` and after `reset()` |

Context on `session_start`: device model, locale, time zone, network type, battery saver, dark mode, notification permission and days since install.

Every event carries an install ID, the anonymous ID, your user ID (after `identify`), a session ID, and app, OS and SDK versions.

**Only subscribers are tracked.** Users without an active subscription or trial are not stored or sent; events wait in memory until the subscription state is known.

**Subscription state.** The SDK observes Play Billing only (`queryPurchasesAsync`, `queryProductDetailsAsync`). It never launches a purchase, acknowledges or consumes one. Some values Play doesn't give the device (trial status, expiry date, expiry itself) are estimates and are marked as such in the event.

## Privacy and permissions

- **No runtime permissions.** The library manifest declares only `ACCESS_NETWORK_STATE` and `INTERNET`, both granted at install. No location, sensors, advertising ID, contacts, clipboard, package listing or permission prompts. Notification permission is read, never requested.
- The Play Billing Library adds Google's own `BILLING` permission and components to any app that includes it.
- **Install ID:** a UUID derived from a salted SHA-256 of `ANDROID_ID` and your package name. The raw value never leaves the device. It survives a reinstall; declare it in Play's Data safety form as a device identifier. If `ANDROID_ID` is unavailable, a random ID is used.
- **Storage:** the queue (at most 5,000 events) sits in the app's no-backup directory. State lives in the SDK's own preferences file, which is wiped if Android's backup restores it into another install.
- The API key and event payloads are never logged.

## Build and test

```sh
./gradlew :renoo:testDebugUnitTest   # JVM and Robolectric unit tests
./gradlew :renoo:assembleDebug       # build the AAR
```

## Licence

Apache License 2.0. See [LICENSE](LICENSE).
