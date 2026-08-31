package com.fakegps.mocklocation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fakegps.mocklocation.util.AppReviewManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppReviewManagerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        AppReviewManager.resetForTesting(context)
    }

    @Test
    fun testInitialState_doesNotPromptImmediately() {
        assertFalse(AppReviewManager.hasUserReviewed(context))
        assertFalse(AppReviewManager.hasUserNeverAskAgain(context))
        assertFalse(AppReviewManager.shouldPromptReview(context))
    }

    @Test
    fun testActionThreshold_promptsAfterThreeActions() {
        assertFalse(AppReviewManager.shouldPromptReview(context))

        val prefs = context.getSharedPreferences("nowhere_review_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("key_review_successful_action_count", 3).apply()

        assertTrue(AppReviewManager.shouldPromptReview(context))
    }

    @Test
    fun testLaunchThreshold_promptsAfterFourLaunches() {
        for (i in 1..4) {
            AppReviewManager.incrementLaunchCount(context)
        }
        assertTrue(AppReviewManager.shouldPromptReview(context))
    }

    @Test
    fun testOnUserRated_permanentlyDisablesPrompt() {
        val prefs = context.getSharedPreferences("nowhere_review_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("key_review_successful_action_count", 5).apply()
        assertTrue(AppReviewManager.shouldPromptReview(context))

        AppReviewManager.onUserRated(context)

        assertTrue(AppReviewManager.hasUserReviewed(context))
        assertFalse(AppReviewManager.shouldPromptReview(context))
    }

    @Test
    fun testOnNeverAskAgain_permanentlyDisablesPrompt() {
        val prefs = context.getSharedPreferences("nowhere_review_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("key_review_successful_action_count", 5).apply()
        assertTrue(AppReviewManager.shouldPromptReview(context))

        AppReviewManager.onNeverAskAgain(context)

        assertTrue(AppReviewManager.hasUserNeverAskAgain(context))
        assertFalse(AppReviewManager.shouldPromptReview(context))
    }

    @Test
    fun testOnRemindLater_enforcesCooldown() {
        val prefs = context.getSharedPreferences("nowhere_review_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("key_review_successful_action_count", 5).apply()
        assertTrue(AppReviewManager.shouldPromptReview(context))

        AppReviewManager.onRemindLater(context)

        // Immediately after remind later, cooldown is active -> should NOT prompt
        assertFalse(AppReviewManager.shouldPromptReview(context))

        // Fast forward 4 days (345_600_000 ms) in the past
        val fourDaysAgo = System.currentTimeMillis() - (4 * 24 * 60 * 60 * 1000L)
        prefs.edit().putLong("key_last_review_prompt_time", fourDaysAgo).apply()

        // Now cooldown has elapsed -> should prompt again
        assertTrue(AppReviewManager.shouldPromptReview(context))
    }
}
