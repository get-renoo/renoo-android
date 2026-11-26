package dev.renoo.sdk.collectors

/**
 * Decides whether an Activity that just resumed is an app screen, and what it is called.
 * Pure (class names only, no Android types) so the rules are unit-tested on the JVM; the
 * [ScreenCollector] glue fills in the names. The Android counterpart of the iOS `ScreenFilter`.
 */
internal object ScreenFilter {
    /**
     * The single native Activity a cross-platform framework renders everything inside. Not a
     * screen: the framework's wrapper reports the real screens by name. Matched against the
     * Activity's own class and every superclass, by name, so the SDK has no compile-time
     * dependency on Flutter or React Native.
     */
    val wrapperHostClassNames: Set<String> = setOf(
        "io.flutter.embedding.android.FlutterActivity",
        "io.flutter.embedding.android.FlutterFragmentActivity",
        // Flutter's v1 embedding (removed in Flutter 3.x, still in old apps).
        "io.flutter.app.FlutterActivity",
        "io.flutter.app.FlutterFragmentActivity",
        "com.facebook.react.ReactActivity",
    )

    /**
     * Packages whose own Activities are system or library UI, not app screens: Play Billing's
     * `com.android.billingclient.api.ProxyBillingActivity`, Google sign-in and ads
     * (`com.google.android.gms.…`), Play in-app review / updates (`com.google.android.play.…`),
     * Firebase Auth, Facebook Login, AndroidX helpers (e.g. Credential Manager's hidden activity).
     * Matched against the Activity's own class only: app Activities extend framework classes.
     */
    val libraryPackagePrefixes: List<String> = listOf(
        "android.",
        "androidx.",
        "com.android.",
        "com.google.android.",
        "com.google.firebase.",
        "com.facebook.",
    )

    /**
     * The screen name, or `null` when the Activity should not produce a `screen_view`.
     *
     * @param className the Activity's binary class name (`Class.getName()`), e.g. `com.example.app.HomeActivity`.
     * @param superclassNames its superclasses' binary names, nearest first (may stop at `android.app.Activity`).
     */
    fun screenName(className: String, superclassNames: List<String>): String? {
        if (className.isBlank()) return null
        if (className in wrapperHostClassNames || superclassNames.any { it in wrapperHostClassNames }) return null
        if (libraryPackagePrefixes.any { className.startsWith(it) }) return null
        return simpleName(className)
    }

    /**
     * `com.example.HomeActivity` → `HomeActivity`; nested `com.example.Outer$Inner` → `Outer.Inner`
     * (the iOS filter keeps the outer type the same way). `null` when nothing is left.
     */
    fun simpleName(className: String): String? {
        val name = className.substringAfterLast('.').replace('$', '.').trim('.', ' ')
        return name.ifEmpty { null }
    }
}
