package com.rewardadguard.app.detector

import android.content.Context
import com.rewardadguard.app.manager.RewardAppStore

/**
 * Core source-based rule (spec sections 16-17).
 *
 * A redirect is only ever considered when ALL of the following hold:
 *  1. the *previous* foreground package is one of the user's selected reward apps,
 *  2. a reward session is active for that package,
 *  3. the foreground package changed,
 *  4. the new package is not the source package itself.
 *
 * Consequence: `Chrome -> Play Store`, `LINE -> Chrome` and `Chrome -> Game` are
 * never classified as redirects, because Chrome/LINE are not monitored sources.
 */
class RedirectDetector(
    private val context: Context,
    private val rewardAppStore: RewardAppStore,
    private val classifier: PackageClassifier
) {

    data class Detection(
        val sourcePackage: String,
        val destinationPackage: String,
        val kind: DestinationKind,
        val detectionMethod: String
    ) {
        val isExternal: Boolean get() = kind.isExternal
    }

    /**
     * @param previousPackage foreground package before the change
     * @param currentPackage  foreground package after the change
     * @param sessionSourcePackage the package the running session belongs to
     * @param sessionActive whether a reward session is currently running
     * @param uriScheme optional scheme of the URI that triggered the transition
     */
    fun detect(
        previousPackage: String?,
        currentPackage: String?,
        sessionSourcePackage: String?,
        sessionActive: Boolean,
        uriScheme: String? = null
    ): Detection? {
        if (!sessionActive || sessionSourcePackage == null) return null
        if (currentPackage == null || previousPackage == null) return null
        if (currentPackage == previousPackage) return null

        // (1) the session source must be the app we are leaving
        if (previousPackage != sessionSourcePackage) return null

        // (2) the session source must still be a monitored reward app
        if (!rewardAppStore.isMonitored(sessionSourcePackage)) return null

        // (3) our own UI taking the foreground is not an external redirect
        if (currentPackage == context.packageName) return null

        // (4) the destination must actually differ from the source
        if (currentPackage == sessionSourcePackage) return null

        val kind = classifier.classify(currentPackage, uriScheme)
        if (kind == DestinationKind.SELF || kind == DestinationKind.UNKNOWN) return null

        return Detection(
            sourcePackage = sessionSourcePackage,
            destinationPackage = currentPackage,
            kind = kind,
            detectionMethod = "SOURCE_BASED_FOREGROUND_TRANSITION"
        )
    }
}
