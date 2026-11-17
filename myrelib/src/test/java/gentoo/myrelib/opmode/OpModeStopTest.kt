package gentoo.myrelib.opmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpModeStopTest {
    @Test
    fun `stop attempts every hook for every combination of failures`() {
        for(mask in 0..7) {
            val events = mutableListOf<Int>()
            val failures = List(3) { AssertionError("hook $it failed") }
            fun hook(index: Int) {
                events += index
                if(mask and (1 shl index) != 0) throw failures[index]
            }
            val opMode = object : OpMode() {
                override fun playStopPre() = hook(0)
                override fun playStop() = hook(1)
                override fun playStopPost() = hook(2)
            }
            val expectedFailures = failures.filterIndexed { index, _ ->
                mask and (1 shl index) != 0
            }

            if(expectedFailures.isEmpty()) {
                opMode.stop()
            }
            else {
                val thrown = assertThrows(AssertionError::class.java) { opMode.stop() }
                assertSame(expectedFailures.first(), thrown)
                assertEquals(expectedFailures.drop(1), thrown.suppressed.toList())
            }
            assertEquals(listOf(0, 1, 2), events)
        }
    }

    @Test
    fun `shared failure does not prevent later hooks or suppress itself`() {
        val failure = IllegalStateException("shared failure")
        val events = mutableListOf<Int>()
        val opMode = object : OpMode() {
            override fun playStopPre() { events += 0; throw failure }
            override fun playStop() { events += 1; throw failure }
            override fun playStopPost() { events += 2; throw failure }
        }

        assertSame(failure, assertThrows(IllegalStateException::class.java) { opMode.stop() })
        assertEquals(listOf(0, 1, 2), events)
        assertTrue(failure.suppressed.isEmpty())
    }
}
