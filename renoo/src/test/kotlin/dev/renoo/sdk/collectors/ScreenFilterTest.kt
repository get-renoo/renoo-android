package dev.renoo.sdk.collectors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenFilterTest {
    private val activity = listOf("androidx.appcompat.app.AppCompatActivity", "androidx.fragment.app.FragmentActivity", "androidx.activity.ComponentActivity")

    @Test
    fun appActivitiesUseTheirSimpleName() {
        assertEquals("HomeActivity", ScreenFilter.screenName("com.example.app.HomeActivity", activity))
        assertEquals("PaywallActivity", ScreenFilter.screenName("com.revenuecat.purchases.ui.revenuecatui.activity.PaywallActivity", activity))
        assertEquals("Outer.Inner", ScreenFilter.screenName("com.example.app.Outer\$Inner", emptyList()))
        assertEquals("NoPackage", ScreenFilter.screenName("NoPackage", emptyList()))
        // A package that merely contains "android" is an app package.
        assertEquals("MainActivity", ScreenFilter.screenName("com.example.android.MainActivity", activity))
    }

    @Test
    fun crossPlatformHostsAreSkippedThroughAnySuperclass() {
        assertNull(ScreenFilter.screenName("io.flutter.embedding.android.FlutterActivity", emptyList()))
        assertNull(ScreenFilter.screenName("com.example.app.MainActivity", listOf("io.flutter.embedding.android.FlutterActivity")))
        assertNull(
            ScreenFilter.screenName(
                "com.example.app.MainActivity",
                listOf("com.example.app.BaseFlutter", "io.flutter.embedding.android.FlutterFragmentActivity", "androidx.fragment.app.FragmentActivity"),
            ),
        )
        assertNull(ScreenFilter.screenName("com.example.app.MainActivity", listOf("io.flutter.app.FlutterActivity")))
        assertNull(ScreenFilter.screenName("com.example.app.MainActivity", listOf("com.facebook.react.ReactActivity", "androidx.appcompat.app.AppCompatActivity")))
    }

    @Test
    fun systemAndLibraryActivitiesAreSkipped() {
        for (name in listOf(
            "com.android.billingclient.api.ProxyBillingActivity",
            "com.android.billingclient.api.ProxyBillingActivityV2",
            "com.google.android.gms.auth.api.signin.internal.SignInHubActivity",
            "com.google.android.gms.common.api.GoogleApiActivity",
            "com.google.android.gms.ads.AdActivity",
            "com.google.android.play.core.common.PlayCoreDialogWrapperActivity",
            "com.google.firebase.auth.internal.GenericIdpActivity",
            "com.facebook.FacebookActivity",
            "androidx.credentials.playservices.HiddenActivity",
            "android.app.Activity",
            "com.android.internal.app.ResolverActivity",
        )) {
            assertNull(name, ScreenFilter.screenName(name, emptyList()))
        }
    }

    @Test
    fun emptyNamesAreSkipped() {
        assertNull(ScreenFilter.screenName("", emptyList()))
        assertNull(ScreenFilter.screenName("com.example.", emptyList()))
    }
}
