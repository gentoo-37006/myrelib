package gentoo.myrelib.hardware

import com.qualcomm.robotcore.hardware.CRServoImplEx
import com.qualcomm.robotcore.hardware.DcMotorSimple.Direction
import gentoo.myrelib.component.HardwareAccess
import kotlin.math.abs

class CrServo(hardware: HardwareAccess, name: String) {
    constructor(hardware: HardwareAccess, name: String, config: CrServo.() -> Unit) : this(
        hardware,
        name
    ) {
        config()
    }

    private val servo: CRServoImplEx? = hardware.get(name)
    /** null until this wrapper has commanded the device in its current direction */
    private var lastPower: Double? = null
    private val powerThreshold: Double = HardwareDefaults.POWER_WRITE_THRESHOLD

    var power: Double
        get() = lastPower ?: 0.0
        set(power) {
            if(power.isNaN()) return
            val last = lastPower
            if(last == null || abs(power - last) > powerThreshold || (power == 0.0 && last != 0.0)) {
                servo?.power = power
                lastPower = power
            }
        }

    var direction: Direction
        get() = servo?.direction ?: Direction.FORWARD
        set(value) {
            if(direction != value) {
                servo?.direction = value
                lastPower = null
            }
        }
}
