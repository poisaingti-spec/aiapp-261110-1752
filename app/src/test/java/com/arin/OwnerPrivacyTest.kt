package com.arin

import com.arin.selffix.Delegation.Brief
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The person's own words go only to the main model (synthetic messages). */
class OwnerPrivacyTest {
    private val owner = listOf(
        "Please build me a habit tracker for my daughter Priya, she is seven and loves purple unicorns",
        "make the background dark blue please",
        "e: file:///p/app/src/main/java/a/MainActivity.kt:12:5 Unresolved reference 'Habit'.",
    )

    @Test fun copiedSentencesAreRemoved() {
        val task = "TASK: build me a habit tracker for my daughter Priya, she is seven and loves purple unicorns. Use Compose."
        val (t, n) = Brief.withoutOwnerText(task, owner)
        assertTrue(n >= 1); assertFalse(t.contains("Priya")); assertFalse(t.contains("unicorns"))
        assertTrue(t.contains("TASK:")); assertTrue(t.contains("Use Compose."))
    }

    @Test fun aShortMessageCopiedWholeIsRemoved() {
        val (t, n) = Brief.withoutOwnerText("Change the theme: make the background dark blue please. Keep the rest.", owner)
        assertEquals(1, n); assertFalse(t.contains("dark blue please")); assertTrue(t.contains("Keep the rest."))
    }

    @Test fun pastedErrorsAndCodeStay() {
        val task = "Fix this:\ne: file:///p/app/src/main/java/a/MainActivity.kt:12:5 Unresolved reference 'Habit'.\nval x = Habit()"
        val (t, n) = Brief.withoutOwnerText(task, owner)
        assertEquals(0, n); assertEquals(task, t)
    }

    @Test fun theMainModelsOwnWordsStay() {
        val brief = "1. A habit tracker for a child: list of habits, tick each day.\n2. Purple theme with a unicorn icon."
        assertEquals(brief, Brief.withoutOwnerText(brief, owner).first)
    }

    @Test fun briefBuildRemovesOwnerWordsAndSecrets() {
        val b = Brief.build("User: hi\nbuild me a habit tracker for my daughter Priya, she is seven and loves purple unicorns\nkey sk-test-ABCDEFGHIJKLMNOPQRSTUV123456",
            emptyList(), emptyList(), owner)
        assertFalse(b.text.contains("Priya")); assertFalse(b.text.contains("sk-test-ABCDEFGHIJ"))
        assertEquals(1, b.ownerPassagesRemoved); assertEquals(1, b.transcriptLinesRemoved)
    }
}
