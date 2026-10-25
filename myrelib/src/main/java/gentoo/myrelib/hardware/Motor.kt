package gentoo.myrelib.hardware

import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.DcMotorSimple.Direction
import gentoo.myrelib.component.HardwareAccess
import kotlin.math.abs

class Motor(hardware: HardwareAccess, name: String) {
    constructor(hardware: HardwareAccess, name: String, config: Motor.() -> Unit) : this(
        hardware,
        name
    ) {
        config()
    }

    private val motor: DcMotorEx? = hardware.get(name)
    /** null until this wrapper has commanded the device in its current direction */
    private var lastPower: Double? = null
    private val powerThreshold: Double = HardwareDefaults.POWER_WRITE_THRESHOLD

    var power: Double
        get() = lastPower ?: 0.0
        set(power) {
            if(power.isNaN()) return
            val last = lastPower
            if(last == null || abs(power - last) > powerThreshold || (power == 0.0 && last != 0.0)) {
                motor?.power = power
                lastPower = power
            }
        }

    val position: Int get() = motor?.currentPosition ?: 0

    var direction: Direction
        get() = motor?.direction ?: Direction.FORWARD
        set(value) {
            if(direction != value) {
                motor?.direction = value
                lastPower = null
            }
        }

    var mode: DcMotor.RunMode
        get() = motor?.mode ?: DcMotor.RunMode.RUN_WITHOUT_ENCODER
        set(value) {
            motor?.mode = value
        }

    var zeroPowerBehavior: DcMotor.ZeroPowerBehavior
        get() = motor?.zeroPowerBehavior ?: DcMotor.ZeroPowerBehavior.FLOAT
        set(value) {
            motor?.zeroPowerBehavior = value
        }
}
