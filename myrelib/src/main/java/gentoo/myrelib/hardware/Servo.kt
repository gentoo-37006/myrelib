package gentoo.myrelib.hardware

import com.qualcomm.robotcore.hardware.Servo.Direction
import com.qualcomm.robotcore.hardware.Servo as SdkServo
import gentoo.myrelib.hardware.HardwareAccess
import kotlin.math.abs

class Servo(hardware: HardwareAccess, name: String) {
    constructor(hardware: HardwareAccess, name: String, config: Servo.() -> Unit) : this(
        hardware,
        name
    ) {
        config()
    }

    private val servo: SdkServo? = hardware.get(name)

    /** null until the servo has been commanded in its current direction */
    private var lastPosition: Double? = null
    private val positionThreshold: Double = HardwareDefaults.POSITION_WRITE_THRESHOLD

    var position: Double
        get() = lastPosition ?: 0.0
        set(position) {
            if(position.isNaN()) return
            val last = lastPosition
            if(last == null || abs(position - last) > positionThreshold) {
                servo?.position = position
                lastPosition = position
            }
        }

    var direction: Direction
        get() = servo?.direction ?: Direction.FORWARD
        set(value) {
            if(direction != value) {
                servo?.direction = value
                lastPosition = null
            }
        }
}
