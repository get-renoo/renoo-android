# R8 rules applied to apps that use the Renoo SDK.
#
# The SDK uses no reflection, so its own classes may be shrunk and obfuscated freely.
#
# Automatic screen_view capture skips the single host Activity of Flutter and React Native apps by
# matching its superclass *name* (ScreenFilter.wrapperHostClassNames). Manifest-declared Activities
# keep their names anyway, but these base classes are not in the manifest: keep their names so the
# check still works in a minified app. Names only; nothing is kept from being shrunk.
-keepnames class io.flutter.embedding.android.FlutterActivity
-keepnames class io.flutter.embedding.android.FlutterFragmentActivity
-keepnames class io.flutter.app.FlutterActivity
-keepnames class io.flutter.app.FlutterFragmentActivity
-keepnames class com.facebook.react.ReactActivity
