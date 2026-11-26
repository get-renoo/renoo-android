# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-11-26

### Added

- `Renoo.configure(context, configuration)` with a public SDK key and a purchase handler; runs in the main process only and never throws.
- Automatic capture with zero runtime permissions: `install`, `app_open` (cold and warm), `app_background`, `session_start` (with device context), `session_end`, `unclean_exit`, `screen_view` (activities, plus `Renoo.trackScreen`), `push_permission_changed`.
- Subscription state from Play Billing, observe only: `subscription_state` (including `suspended` and inferred `expired`) and `transaction`, with the purchase token and account token.
- Identity: install ID, `Renoo.anonymousId`, `Renoo.identify(userId)`, `Renoo.reset()` and the `identify` event.
- Tracking gate: only users with an active subscription or trial are stored and sent.
- `Renoo.optOut()` and `Renoo.optIn()`.
- On-disk event queue in the no-backup directory, at most 5,000 events.
- Uploader: gzipped NDJSON batches to `POST /events/sdk` with `X-Renoo-Batch-Id` and `X-Renoo-Sent-At` headers, and flush triggers, backoff, `Retry-After` and batch splitting.
- `GET /health-check` on every start-up; a `401` (here or on upload) turns the SDK off until the next start-up and deletes the queue.
- Published to Maven Central as `dev.renoo:renoo-android`, with consumer R8 rules.
- `GET /offers` on start-up and on return to the foreground (at most every 6 hours), with `Renoo.pendingOffer` and `Renoo.addPendingOfferObserver`.
- Events use `schema_version` 1.
