package gentoo.myrelib.processor

import com.qualcomm.robotcore.eventloop.opmode.Autonomous
import gentoo.myrelib.pathing.Auton
import gentoo.myrelib.util.Alliance
import java.io.File

fun main(args: Array<String>) {
    val output = File(args.single())
    val alliances = Alliance.entries.joinToString { "\"${it.name}\"" }
    output.parentFile.mkdirs()
    output.writeText(
        """
        package gentoo.myrelib.processor

        internal const val AUTON_ANNOTATION = "${requireNotNull(Auton::class.qualifiedName)}"
        internal const val ALLIANCE_CLASS = "${requireNotNull(Alliance::class.qualifiedName)}"
        internal const val FTC_AUTONOMOUS_ANNOTATION = "${requireNotNull(Autonomous::class.qualifiedName)}"
        internal val ALLIANCE_NAMES = listOf($alliances)
        """.trimIndent() + "\n"
    )
}
