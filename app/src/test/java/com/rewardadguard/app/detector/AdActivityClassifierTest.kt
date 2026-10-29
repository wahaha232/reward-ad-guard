package com.rewardadguard.app.detector

import com.rewardadguard.app.detector.AdActivityClassifier.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Activity names below are copied from the manifests of BitWalk
 * (`jp.paddleinc.walk`) and 懂球帝 (`com.dongqiudi.news`) as installed on the
 * test phone on 2026-10-29.
 */
class AdActivityClassifierTest {

    private fun walk(cls: String) = AdActivityClassifier.classify(cls, "jp.paddleinc.walk")
    private fun dqd(cls: String) = AdActivityClassifier.classify(cls, "com.dongqiudi.news")

    @Test
    fun `full screen ad activities of the BitWalk SDK stack are ads`() {
        listOf(
            "com.google.android.gms.ads.AdActivity",
            "com.moloco.sdk.xenoss.sdkdevkit.android.adrenderer.internal.templates.renderer.fullscreen.FullscreenWebviewActivity",
            "com.moloco.sdk.xenoss.sdkdevkit.android.adrenderer.internal.vast.VastActivity",
            "com.unity3d.ads.adplayer.FullScreenWebViewDisplay",
            "com.applovin.adview.AppLovinFullscreenActivity",
            "com.bytedance.sdk.openadsdk.activity.single.TTRewardVideoActivity",
            "com.vungle.ads.internal.ui.VungleActivity",
            "com.fyber.inneractive.sdk.activities.InneractiveFullscreenAdActivity",
            "com.mbridge.msdk.reward.player.MBRewardVideoActivity",
            "com.inmobi.ads.rendering.InMobiAdActivity",
            "com.facebook.ads.AudienceNetworkActivity",
            "com.ironsource.sdk.controller.InterstitialActivity",
            "jp.maio.sdk.android.v2.AdActivity"
        ).forEach { assertEquals(it, Kind.AD, walk(it)) }
    }

    @Test
    fun `ad SDK browser and store pages are landing pages`() {
        listOf(
            "com.bytedance.sdk.openadsdk.activity.single.TTLandingPageActivity",
            "com.inmobi.ads.rendering.InMobiInAppBrowserActivity",
            "com.fyber.inneractive.sdk.activities.InneractiveInternalBrowserActivity",
            "com.fyber.inneractive.sdk.activities.InternalStoreWebpageActivity",
            "com.ironsource.sdk.controller.OpenUrlActivity",
            "com.unity3d.ironsourceads.internal.services.InlineStoreActivity",
            "com.applovin.sdk.AppLovinWebViewActivity"
        ).forEach { assertEquals(it, Kind.AD_LANDING, walk(it)) }
    }

    @Test
    fun `the reward app own screens and non ad SDKs are not ads`() {
        listOf(
            "jp.paddleinc.walk.MainActivity",
            "com.google.android.gms.auth.api.signin.internal.SignInHubActivity",
            "com.pichillilorenzo.flutter_inappwebview_android.in_app_browser.InAppBrowserActivity",
            "io.adjoe.sdk.internal.AdjoeActivity",
            "com.applovin.mediation.MaxDebuggerActivity",
            "android.widget.FrameLayout",
            "",
            "Dialog"
        ).forEach { assertEquals(it, Kind.OTHER, walk(it)) }
        listOf(
            "com.dongqiudi.news.PersonalInfoCenterCustomAdActivity",
            "com.dongqiudi.mall.ui.AddressAddActivity",
            "com.dongqiudi.news.fullscreen.VideoFullScreenActivity",
            "com.dongqiudi.group.ThreadInfoActivity"
        ).forEach { assertEquals(it, Kind.OTHER, dqd(it)) }
    }

    @Test
    fun `third party activities named as ads are ads even from unknown SDKs`() {
        assertEquals(Kind.AD, dqd("com.example.newsdk.RewardedVideoActivity"))
        assertEquals(Kind.OTHER, dqd("com.example.newsdk.ShareActivity"))
        assertEquals(Kind.AD, dqd("com.baidu.mobads.sdk.api.MobRewardVideoActivity"))
    }

    @Test
    fun `camel tokens split acronyms and words`() {
        assertEquals(listOf("TT", "Reward", "Video", "Activity"), AdActivityClassifier.camelTokens("TTRewardVideoActivity"))
    }
}
