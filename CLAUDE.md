# CLAUDE.md

Guidance for AI coding assistants working in this repo: the Renoo Android SDK (`renoo.dev`), an Android library that captures in-app behaviour for subscription apps. The iOS SDK ([renoo-ios](https://github.com/get-renoo/renoo-ios)) has the same events and the same public API shape; keep them aligned. Licence: Apache-2.0 (`LICENSE`).

## Hard rules

1. **Zero runtime permissions.** The library manifest may declare only `ACCESS_NETWORK_STATE` and `INTERNET` (normal, install-time permissions). No location, sensors, contacts, clipboard, advertising ID, `QUERY_ALL_PACKAGES`, and no permission prompts. Reading notification status is allowed; requesting it is not.
2. **Never crash or block the host app.** No `!!`, no uncaught exceptions out of SDK entry points, no disk / Binder / billing / network work on the main thread: use the SDK's single background executor.
3. **No purchases.** The SDK only observes Play Billing (`queryPurchasesAsync`, and `queryProductDetailsAsync` to read trial length and billing period); it never launches a billing flow, acknowledges or consumes a purchase.
4. **Dependencies:** Kotlin stdlib and the Play Billing Library only. No AndroidX, no Compose, no JSON, HTTP or coroutine libraries in the shipped artifact (`org.json` and `HttpURLConnection` are part of Android). Test-only dependencies are fine.
5. **Nothing sensitive** in this repo or its history: no keystores, signing configs, API keys, `google-services.json`, personal emails or absolute paths.
6. **Event contract.** Events follow `schema_version` 1 exactly: every listed property present, `null` when unknown. Changes are additive only (new events, properties or enum values); never rename or remove a field. Event and field names, and the `X-Renoo-*` headers on uploads and offer requests are a wire contract shared with iOS and the backend.
7. **Public API:** `explicitApi()` is on. Public types live in `dev.renoo.sdk`; everything under `core/`, `collectors/` and `upload/` is `internal`. The version constant is `Renoo.SDK_VERSION`; record user-visible changes in `CHANGELOG.md` (Keep a Changelog, Semantic Versioning).

## Layout

- `renoo/src/main/kotlin/dev/renoo/sdk/Renoo.kt`: public API (`configure`, `identify`, `reset`, `optOut` / `optIn`, `anonymousId`, `trackScreen`, `pendingOffer`, observers).
- `.../sdk/core/`: event model, pipeline (tracking gate → seq → disk → observers), on-disk queue, kill switch, pending offers.
- `.../sdk/collectors/`: one file per event source; each is a `Collector` started from `SdkCore.start()`.
- `.../sdk/upload/`: endpoint rules (HTTPS only), `HttpURLConnection` transport, uploader (`POST /events/sdk`, batching, backoff), health check (`GET /health-check`) and offer fetcher (`GET /offers`). Created only when `ingestUrl` is set.
- `test-apps/` is git-ignored: a local test app for visual checks. Never commit it.

## Commands

Toolchain: Gradle 8.14 (wrapper), AGP 8.13.2, Kotlin 2.2.20, JDK 17+ to run Gradle. `local.properties` (`sdk.dir=...`) is local only.

- Unit tests (JVM): `./gradlew :renoo:testDebugUnitTest`
- Build the AAR: `./gradlew :renoo:assembleDebug`

## Tests

- JVM only, never an emulator: an in-memory `FakeSharedPreferences` for the pipeline, Robolectric where a real `Context` is needed, and a fake clock, scheduler and transport for network code. Tests never use the network.
- `EventSchemaV1Test` validates a captured sample of every event type against `event-v1.schema.json`. Set `RENOO_EVENT_SCHEMA` to the schema file's absolute path (e.g. `RENOO_EVENT_SCHEMA=/path/to/event-v1.schema.json ./gradlew :renoo:testDebugUnitTest`); when it is unset or the file is missing, the schema tests are skipped.
- Every behaviour change comes with a test; every response branch of each HTTP call has one.
