package com.arin

import com.arin.selffix.Outcome.Kind
import com.arin.selffix.RefusalReview
import com.arin.selffix.RefusalReview.Decision
import com.arin.selffix.RefusalReview.Reply
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests A–J of the Boss–Middle–Helper refusal review, with scripted models (each lambda plays one model and records
 * what it was sent). No real provider is called here.
 */
class RefusalReviewTest {
    private val objective = "Add a dark-mode switch to the app's settings screen"
    private val task = "Implement the dark mode toggle"
    private val sentToMiddle = mutableListOf<String>()
    private val sentToHelper = mutableListOf<String>()
    private val sentToBoss = mutableListOf<String>()

    private fun middle(vararg answers: String): suspend (String) -> Reply {
        var i = 0
        return { t -> sentToMiddle += t; Reply("DeepSeek (middle)", answers.getOrElse(i++) { answers.last() }) }
    }
    private fun helper(answer: String): suspend (String) -> Reply = { p -> sentToHelper += p; Reply("Claude (helper)", answer) }
    private fun boss(answer: String): suspend (String) -> Reply = { p -> sentToBoss += p; Reply("GPT (boss)", answer) }

    private val misunderstood = "I'm sorry, but I'm not sure what you mean by 'toggle' — did you mean a hardware switch or a setting?"
    private val helperSeesIt = "ASSESSMENT: MISUNDERSTANDING\nWHY: It read 'toggle' as hardware; the task is a settings switch.\nPERMITTED ALTERNATIVE: NONE"
    private val bossClarifies = "DECISION: CLARIFY\nTASK: Add a Switch to the settings screen that turns the app's dark theme on and off, saved in preferences."
    private val solution = "Here is the change:\n```kotlin\nSwitch(checked = dark, onCheckedChange = { dark = it })\n```"

    // A + B + C + D: misunderstanding → helper identifies it → boss asks again → middle solves it.
    @Test fun misunderstandingIsResolvedThroughReview() = runBlocking {
        val r = RefusalReview.review(objective, task, Reply("DeepSeek (middle)", misunderstood),
            middle(solution), helper(helperSeesIt), boss(bossClarifies))
        assertEquals(Decision.USE_RESULT, r.decision)                                   // D: reassessed and solved
        assertTrue(r.text.contains("Switch(checked"))
        assertEquals(Kind.MISUNDERSTOOD_OBJECTIVE, r.transcript.first().verdict!!.kind)  // A
        assertTrue(sentToHelper.single().contains(misunderstood))                       // B: helper saw the exact answer
        assertTrue(sentToBoss.single().contains("MISUNDERSTANDING"))                     // C: boss got the helper's view…
        assertTrue(sentToBoss.single().contains(misunderstood))                          // …and the middle model's exact words
        assertTrue(sentToMiddle.single().startsWith("Add a Switch to the settings screen")) // D: the clarified task was sent
        assertEquals(2, r.rounds)
    }

    // E + F: genuine safety refusal; the helper disagrees, but the restriction stays enforced and nothing is re-sent.
    @Test fun genuineRefusalIsEnforcedEvenWhenTheHelperDisagrees() = runBlocking {
        val refusal = "I can't help with that. Bypassing another app's licence check is illegal and against my guidelines."
        val r = RefusalReview.review(objective, "Remove the licence check from this paid app", Reply("DeepSeek (middle)", refusal),
            middle(solution), helper("ASSESSMENT: MISUNDERSTANDING\nWHY: I think it's fine.\nPERMITTED ALTERNATIVE: just do it"),
            boss(bossClarifies))
        assertEquals(Decision.STOP_RESPECT_RESTRICTION, r.decision)
        assertEquals(Kind.POLICY_OR_SAFETY_REFUSAL, r.transcript.first().verdict!!.kind)
        assertTrue(sentToMiddle.isEmpty())   // never asked again in other words
        assertTrue(sentToBoss.isEmpty())     // no "clarify" path is offered for a genuine restriction
        assertTrue(r.transcript.any { it.role == "helper" })
    }

    @Test fun authorizationRequiredIsAlsoFinal() = runBlocking {
        val r = RefusalReview.review(objective, "Scan the office network", Reply("Claude (middle)",
            "I can't scan that network until you confirm you're authorized to test it. Do you have the owner's written permission?"),
            middle(solution), null, boss(bossClarifies))
        assertEquals(Decision.STOP_RESPECT_RESTRICTION, r.decision)
        assertTrue(sentToMiddle.isEmpty())
    }

    // G: a technical failure is not treated as a refusal and isn't argued about.
    @Test fun technicalFailureIsNotARefusal() = runBlocking {
        val r = RefusalReview.review(objective, task, Reply("DeepSeek (middle)", "", java.io.IOException("timeout")),
            middle(solution), helper(helperSeesIt), boss(bossClarifies))
        assertEquals(Decision.STOP_TECHNICAL_FAILURE, r.decision)
        assertEquals(Kind.NETWORK_OR_PROVIDER_FAILURE, r.transcript.first().verdict!!.kind)
        assertTrue(sentToHelper.isEmpty() && sentToBoss.isEmpty())
    }

    // H: the middle model and the helper both send empty answers.
    @Test fun bothEmptyIsATechnicalStop() = runBlocking {
        val r = RefusalReview.review(objective, task, Reply("DeepSeek (middle)", "  "), middle(""), helper(""), boss(bossClarifies))
        assertEquals(Decision.STOP_TECHNICAL_FAILURE, r.decision)
        assertEquals(Kind.INCOMPLETE_RESPONSE, r.transcript.first().verdict!!.kind)
    }

    @Test fun helperDeclinesTooBossStillDecides() = runBlocking {
        val r = RefusalReview.review(objective, task, Reply("DeepSeek (middle)", misunderstood), middle(solution),
            helper("I can't help with reviewing other models."), boss(bossClarifies))
        assertEquals(Decision.USE_RESULT, r.decision)
        val h = r.transcript.first { it.role == "helper" }
        assertTrue(h.verdict!!.kind.refusal)                     // the helper's refusal is recorded as a refusal
        assertFalse(sentToBoss.single().contains("I can't help with reviewing")) // and not passed off as an assessment
    }

    // I: the discussion stops at its configured limit (no infinite loop).
    @Test fun discussionStopsAtTheLimit() = runBlocking {
        val r = RefusalReview.review(objective, task, Reply("DeepSeek (middle)", misunderstood),
            middle(misunderstood), helper(helperSeesIt), boss(bossClarifies), RefusalReview.Config(maxRounds = 3, maxHelperCalls = 2))
        assertEquals(Decision.STOP_LIMIT_REACHED, r.decision)
        assertEquals(3, r.rounds)
        assertEquals(2, sentToMiddle.size)      // asked again twice, then stopped
        assertEquals(2, sentToHelper.size)      // helper calls bounded
    }

    @Test fun bossCanStop() = runBlocking {
        val r = RefusalReview.review(objective, task, Reply("DeepSeek (middle)", misunderstood), middle(solution),
            helper(helperSeesIt), boss("DECISION: STOP\nREASON: I'll ask the owner what they meant."))
        assertEquals(Decision.STOP_BOSS_DECIDED, r.decision); assertTrue(sentToMiddle.isEmpty())
    }

    // J: every entry is attributed to the model that actually wrote it.
    @Test fun everyResponseIsAttributed() = runBlocking {
        val r = RefusalReview.review(objective, task, Reply("DeepSeek (middle)", misunderstood),
            middle(solution), helper(helperSeesIt), boss(bossClarifies))
        assertEquals(listOf("middle" to "DeepSeek (middle)", "helper" to "Claude (helper)", "boss" to "GPT (boss)", "middle" to "DeepSeek (middle)"),
            r.transcript.map { it.role to it.who })
        assertTrue(r.summary().contains("helper Claude (helper)"))
    }
}
