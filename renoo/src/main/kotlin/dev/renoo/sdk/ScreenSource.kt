package dev.renoo.sdk

/** Where a screen view came from. [wireValue] is the `source` property of `screen_view`. */
public enum class ScreenSource(public val wireValue: String) {
    /** Automatic capture of an Activity (`ScreenCollector`, when `automaticScreenTracking` is on). */
    ACTIVITY("activity"),

    /** A Jetpack Compose screen reported by the app or a future Compose helper. */
    COMPOSE("compose"),

    /** [Renoo.trackScreen] called by the app. */
    MANUAL("manual"),

    /** The Flutter wrapper. */
    FLUTTER("flutter"),

    /** The React Native wrapper. */
    REACT_NATIVE("react_native"),
}
