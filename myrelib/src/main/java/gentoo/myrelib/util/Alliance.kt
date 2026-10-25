package gentoo.myrelib.util

import gentoo.myrelib.robot.BaseRobot

enum class Alliance {
    BLUE,
    RED;

    fun forwardHeading() = when(this) {
        BLUE -> -90.0
        RED -> 90.0
    }

    fun opposite() = when(this) {
        BLUE -> RED
        RED -> BLUE
    }

    companion object {
        /**
         * Picks between two alliance specific values.
         *
         * @return one of the two values based on the alliance, or null for an alliance-neutral robot
         * @throws IllegalStateException if the robot has not been initialized
         */
        fun <T> pick(blue: T, red: T): T? = when(BaseRobot.INSTANCE.alliance) {
            BLUE -> blue
            RED -> red
            null -> null
        }
    }
}
