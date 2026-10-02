package com.rewardadguard.app.manager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the matching rule behind the "installed apps" search box.
 *
 * This exists because the bug it guards against was not a wrong predicate but a
 * state bug: the text field was bound to a hard-coded `""`, so the query
 * reaching this filter was always blank and the list never changed. The filter
 * itself is extracted here so at least the matching contract is pinned down.
 */
class CandidateSearchTest {

    private val label = "麥當勞"
    private val pkg = "com.mcdonalds.mobileapp"

    @Test
    fun `blank query matches every app`() {
        // The picker must show the full installed list before anything is typed.
        assertTrue(matchesCandidateQuery(label, pkg, ""))
        assertTrue(matchesCandidateQuery(label, pkg, "   "))
        assertTrue(matchesCandidateQuery(label, pkg, null))
    }

    @Test
    fun `query matches on the visible label`() {
        assertTrue(matchesCandidateQuery(label, pkg, "麥當勞"))
    }

    @Test
    fun `query matches on the package name`() {
        // Users paste package names from an ad report, so this path matters.
        assertTrue(matchesCandidateQuery(label, pkg, "com.mcdonalds"))
    }

    @Test
    fun `matching ignores case`() {
        assertTrue(matchesCandidateQuery("StepsCash", "com.walkearn.stepscash", "stepscash"))
        assertTrue(matchesCandidateQuery("StepsCash", "com.walkearn.stepscash", "STEPS"))
        assertTrue(matchesCandidateQuery("StepsCash", "com.walkearn.stepscash", "WalkEarn"))
    }

    @Test
    fun `surrounding whitespace does not defeat the match`() {
        // A trailing space from a soft keyboard or a paste must not empty the
        // list; the old code passed the raw string straight to contains().
        assertTrue(matchesCandidateQuery(label, pkg, " 麥當勞 "))
        assertTrue(matchesCandidateQuery(label, pkg, " com.mcdonalds "))
    }

    @Test
    fun `non-matching query filters the app out`() {
        assertFalse(matchesCandidateQuery(label, pkg, "spotify"))
        assertFalse(matchesCandidateQuery(label, pkg, "com.spotify.music"))
    }

    @Test
    fun `a partial label is enough`() {
        assertTrue(matchesCandidateQuery("Reward Ad Guard", "com.rewardadguard.app", "ard"))
    }

    @Test
    fun `empty label still matches on package`() {
        // Some pre-installed apps resolve to a blank label; they must stay
        // findable by package name rather than becoming unreachable.
        assertTrue(matchesCandidateQuery("", "com.example.thing", "example"))
        assertFalse(matchesCandidateQuery("", "com.example.thing", "nomatch"))
    }
}
