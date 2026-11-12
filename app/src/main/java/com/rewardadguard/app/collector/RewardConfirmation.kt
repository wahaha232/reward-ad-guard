package com.rewardadguard.app.collector

/**
 * Evidence that a reward was actually credited, not just that an ad ended.
 *
 * An ad closing and the reward arriving are different events: the ad can be
 * skipped, interrupted, or closed before the server pays (review 2026-11-12,
 * P1). A reward counts only with one of two proofs on screen:
 *  1. the app's own reward dialog ("您獲得了BTC!", "恭喜", ...), or
 *  2. the app's balance going up (BITWALK's mBTC).
 */
object RewardConfirmation {

    /** The app's reward dialog is on screen. */
    fun hasRewardDialog(snapshot: Snapshot, profile: AppProfile): Boolean =
        CollectorRules.dialogAnchor(snapshot, profile) != null

    /** The balance the profile can read, or null when it is not on screen. */
    fun balance(snapshot: Snapshot, profile: AppProfile): Double? {
        val pattern = profile.balancePattern ?: return null
        for (box in snapshot.boxes) {
            val match = pattern.find(box.text) ?: continue
            val raw = match.groupValues[1]
                .replace(" ", "")
                .replace('·', '.')
                .replace('．', '.')
            raw.toDoubleOrNull()?.let { return it }
        }
        return null
    }

    /** True when [after] proves a reward compared with [before]. */
    fun confirmed(before: Snapshot, after: Snapshot, profile: AppProfile): Boolean {
        if (hasRewardDialog(after, profile)) return true
        val was = balance(before, profile) ?: return false
        val now = balance(after, profile) ?: return false
        return now > was
    }
}
