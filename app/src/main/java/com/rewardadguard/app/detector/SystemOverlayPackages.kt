package com.rewardadguard.app.detector

/**
 * Packages that draw system surfaces but are never "the app the user is in".
 *
 * Field report 2026-10-30: on HyperOS the dynamic island
 * (`miui.systemui.plugin`) raises `TYPE_WINDOW_STATE_CHANGED` like a normal app.
 * Treating it as the foreground app made the guard (a) schedule a Back press
 * while the reward app never left, closing it, and (b) overwrite the "previous"
 * package, so the Shopee app opened afterwards was no longer recognised as an
 * ad redirect. The intent resolver is the same class of surface: it only hands
 * the URI over to the real destination.
 *
 * The list is explicit on purpose. Substring matching on "plugin" or "resolver"
 * would misclassify ordinary apps, and every consumer must agree on exactly the
 * same set.
 */
object SystemOverlayPackages {

    /** Surfaces that must never become the foreground package. */
    private val OVERLAYS = setOf(
        "com.android.systemui",
        "miui.systemui.plugin",
        "com.android.intentresolver"
    )

    /**
     * Everything that is a system surface for policy purposes: the overlays
     * above plus settings, permission dialogs and package installers. The guard
     * must neither start a session for them nor treat them as a destination.
     */
    private val SYSTEM_SURFACES = OVERLAYS + setOf(
        "com.android.settings",
        "android",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller"
    )

    /**
     * Tools the user summons on top of whatever is on screen: Google search /
     * Circle to Search / Assistant, Gemini and Lens.
     *
     * 2026-11-04: during AHEALTH ads the Google app came to the front straight
     * from the reward app five times without any tap by the guard - the user
     * calling up search or Assistant while waiting - and the guard took it for
     * an ad redirect and pressed Back, closing it. Ads open Chrome or the Play
     * Store, not these; they are treated like the launcher: the user leaving on
     * purpose.
     */
    private val USER_SUMMONED = setOf(
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.googleassistant",
        "com.google.android.apps.bard",
        "com.google.ar.lens"
    )

    /** True for [USER_SUMMONED] tools, which must never be blocked. */
    fun isUserSummoned(packageName: String): Boolean = packageName in USER_SUMMONED

    /** True for floating system windows that sit above the real foreground app. */
    fun isOverlay(packageName: String): Boolean = packageName in OVERLAYS

    /** True for overlays and the other system surfaces listed above. */
    fun isSystemSurface(packageName: String): Boolean = packageName in SYSTEM_SURFACES
}
