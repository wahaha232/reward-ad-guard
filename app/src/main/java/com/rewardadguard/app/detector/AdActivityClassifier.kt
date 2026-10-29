package com.rewardadguard.app.detector

/**
 * Classifies the activity a monitored reward app just brought to the front.
 *
 * This is the guard's ad-window signal (assessment item A4, option 1). The old
 * signal - "a close button was detected" - fired on ordinary app screens (a
 * news feed's dismiss icons kept 懂球帝 in AD_SESSION_ACTIVE for hours, so the
 * user's own switch to LINE was blocked and pulled back), and it never fired
 * for ads whose X is painted inside a WebView or a game engine canvas, so the
 * jumps those ads caused were never blocked.
 *
 * Rewarded and interstitial ads are shown by the ad SDK's own full-screen
 * activity, whose class name is public and stable. Measured on BitWalk (a
 * Flutter app whose only own activity is `jp.paddleinc.walk.MainActivity`):
 * every other declared activity belongs to an ad SDK.
 *
 * Pure Kotlin, no Android APIs, so it is unit tested directly.
 */
object AdActivityClassifier {

    enum class Kind {
        /** A full-screen ad is on screen. */
        AD,

        /**
         * An ad SDK's in-app browser / store page: the ad navigated away from
         * itself without leaving the reward app's task.
         */
        AD_LANDING,

        /** Anything else: the reward app's own UI, a login screen, a picker. */
        OTHER
    }

    fun classify(className: String?, sourcePackage: String?): Kind {
        val cls = className?.trim().orEmpty()
        if (cls.isEmpty() || '.' !in cls) return Kind.OTHER
        if (EXCLUDED_PREFIXES.any { cls.startsWith(it) }) return Kind.OTHER
        val simple = cls.substringAfterLast('.')
        if (DEBUG_TOKENS.any { simple.contains(it) }) return Kind.OTHER

        val knownSdk = AD_SDK_PREFIXES.any { cls.startsWith(it) }
        val ownClass = sourcePackage != null && isOwnClass(cls, sourcePackage)
        val tokens = camelTokens(simple)
        val adNamed = tokens.any { it in AD_TOKENS }

        // The reward app's own screens are never an ad on name alone: 懂球帝 has
        // `PersonalInfoCenterCustomAdActivity` (a settings page).
        if (!knownSdk && (ownClass || !adNamed)) return Kind.OTHER

        if (LANDING_TOKENS.any { simple.contains(it) } && !simple.contains("fullscreen", ignoreCase = true)) {
            return Kind.AD_LANDING
        }
        return Kind.AD
    }

    /**
     * True when [cls] lives in the reward app's own namespace, compared on the
     * first two package segments (`com.dongqiudi.news` owns `com.dongqiudi.*`).
     */
    private fun isOwnClass(cls: String, sourcePackage: String): Boolean {
        if (cls.startsWith("$sourcePackage.")) return true
        val root = sourcePackage.split('.').take(2).joinToString(".")
        return root.contains('.') && cls.startsWith("$root.")
    }

    /** `TTRewardVideoActivity` -> [TT, Reward, Video, Activity]. */
    internal fun camelTokens(simple: String): List<String> =
        Regex("[A-Z]+(?![a-z])|[A-Z][a-z0-9]*|[a-z0-9]+").findAll(simple).map { it.value }.toList()

    /** Words that only an ad activity carries as a whole CamelCase token. */
    private val AD_TOKENS = setOf("Ad", "Ads", "Rewarded", "Reward", "Interstitial", "Mraid", "Vast", "Playable")

    /** An ad SDK screen that is a web page or store page rather than the ad. */
    private val LANDING_TOKENS = listOf(
        "LandingPage", "Browser", "Website", "Webpage", "WebPage", "OpenUrl", "InlineStore", "StoreWebpage",
        "AppLovinWebView"
    )

    /** SDK test and debug screens are only ever opened on purpose. */
    private val DEBUG_TOKENS = listOf("Debugger", "TestSuite", "ReportAd", "AdReport", "Testing")

    /** Third-party screens that are not ads (login, payments, framework). */
    private val EXCLUDED_PREFIXES = listOf(
        "android.", "androidx.", "com.android.",
        "com.google.android.gms.auth.", "com.google.android.gms.common.",
        "com.google.android.play.core.", "com.google.firebase.",
        "com.facebook.FacebookActivity", "com.facebook.CustomTab",
        "io.flutter.", "com.pichillilorenzo.", "dev.fluttercommunity.",
        "com.braze.", "io.adjoe.", "com.alipay.", "com.tencent.connect.",
        "com.tencent.tauth.", "com.huawei.hms.", "com.sina.weibo."
    )

    /** Package prefixes of ad SDKs whose activities are always ad surfaces. */
    private val AD_SDK_PREFIXES = listOf(
        "com.google.android.gms.ads.",
        "com.applovin.adview.", "com.applovin.sdk.AppLovinWebViewActivity",
        "com.unity3d.ads.", "com.unity3d.services.ads.", "com.unity3d.ironsourceads.",
        "com.ironsource.sdk.controller.", "com.ironsource.mediationsdk.",
        "com.moloco.sdk.",
        "com.vungle.",
        "com.fyber.",
        "com.mbridge.msdk.", "com.mintegral.msdk.",
        "com.bytedance.sdk.openadsdk.", "com.pangle.",
        "com.inmobi.ads.",
        "com.facebook.ads.",
        "com.adcolony.sdk.",
        "com.chartboost.sdk.",
        "com.tapjoy.",
        "com.five_corp.ad.",
        "jp.maio.sdk.",
        "com.ogury.ad.",
        "com.my.target.",
        "com.yandex.mobile.ads.",
        "sg.bigo.ads.", "com.bigossp.",
        "com.smaato.", "net.pubnative.", "com.verve.",
        "com.amazon.device.ads.",
        "com.hyprmx.",
        "io.bidmachine.",
        "com.kwad.sdk.",
        "com.qq.e.ads.",
        "com.baidu.mobads.",
        "com.beizi.ad.",
        "com.sigmob.",
        "com.windmill.sdk.",
        "com.meishu.sdk.",
        "com.ubix.", "com.ubixnow.",
        "com.qumeng.advlib.",
        "com.jd.ad.sdk.",
        "com.dydroid.ads.",
        "com.xunmeng.amiibo."
    )
}
