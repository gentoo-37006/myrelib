package gentoo.myrelib.util

import gentoo.myrelib.math.assertAngleEquals
import gentoo.myrelib.math.normalizeAngle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AllianceTest {
    @Test
    fun `the alliances face each other`() {
        // whichever side each is on, they look in opposite directions. this holds
        // even if the two are the wrong way round, which is why it is asserted
        // instead of the absolute headings, which need the field to confirm
        val between = normalizeAngle(
            Alliance.RED.forwardHeading() - Alliance.BLUE.forwardHeading()
        )
        assertEquals(180.0, abs(between), 1e-9)
    }

    @Test
    fun `both forward headings are square to the field`() {
        for(alliance in Alliance.entries) {
            val heading = alliance.forwardHeading()
            assertTrue(
                "$alliance forward heading $heading is not a quarter turn",
                abs(normalizeAngle(heading) % 90.0) < 1e-9
            )
        }
    }

    @Test
    fun `a forward heading survives normalizing`() {
        for(alliance in Alliance.entries) {
            assertAngleEquals(
                alliance.forwardHeading(),
                normalizeAngle(alliance.forwardHeading())
            )
        }
    }

    @Test
    fun `opposite flips the alliance`() {
        assertEquals(Alliance.RED, Alliance.BLUE.opposite())
        assertEquals(Alliance.BLUE, Alliance.RED.opposite())
        for(alliance in Alliance.entries) {
            assertEquals(alliance, alliance.opposite().opposite())
        }
    }
}
