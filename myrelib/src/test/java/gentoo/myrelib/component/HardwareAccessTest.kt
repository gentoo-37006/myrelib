package gentoo.myrelib.component

import com.qualcomm.robotcore.hardware.DcMotorEx
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HardwareAccessTest {
    @Test
    fun `empty scope supports reified lookup without hardware`() {
        val hardware = HardwareAccess.empty()

        assertFalse(hardware.isAvailable)
        assertNull(hardware.get<DcMotorEx>("missing"))
    }
}
