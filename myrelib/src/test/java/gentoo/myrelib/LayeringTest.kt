package gentoo.myrelib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * myrelib is meant to outlive any one season, so it must not depend on the
 * season's own package. The dependency is only allowed to run the other way.
 *
 * This drifts back easily: reaching for `Robot` from inside the library is the
 * path of least resistance every time, and nothing else notices until the code
 * is being lifted into next season's repo. AutonSequence, Path and Alliance all
 * had it.
 */
class LayeringTest {
    private val seasonPackage = listOf("org", "firstinspires", "ftc", "teamcode")
        .joinToString(".")

    /** walks up from the working directory until it finds the repo root */
    private fun repoRoot(): File {
        var candidate: File? = File(System.getProperty("user.dir")).absoluteFile
        while(candidate != null) {
            if(File(candidate, "settings.gradle").isFile) {
                return candidate
            }
            candidate = candidate.parentFile
        }
        throw AssertionError("could not find the repo root from ${System.getProperty("user.dir")}")
    }

    private fun myrelibSources(): List<File> {
        val root = File(repoRoot(), "myrelib/src/main/java/gentoo/myrelib")
        assertTrue("myrelib sources not found at $root", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun myrelibTestSources(): List<File> {
        val root = File(repoRoot(), "myrelib/src/test/java/gentoo/myrelib")
        assertTrue("myrelib test sources not found at $root", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun seasonReferences(files: List<File>): List<String> = files.flatMap { file ->
        file.readLines()
            .withIndex()
            .filter { (_, line) -> seasonPackage in line }
            .map { (index, line) -> "  ${file.name}:${index + 1}  ${line.trim()}" }
    }

    @Test
    fun `the library sources are actually being scanned`() {
        // guards against the test quietly passing because it found nothing
        val sources = myrelibSources()
        assertTrue("expected to find myrelib sources, found ${sources.size}", sources.size > 10)
    }

    @Test
    fun `myrelib does not depend on this season's package`() {
        val offenders = seasonReferences(myrelibSources())

        assertEquals(
            "myrelib should not import $seasonPackage; " +
                "keep reusable framework code independent of season code:\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders
        )
    }

    @Test
    fun `myrelib tests use their own sample data instead of season code`() {
        val offenders = seasonReferences(myrelibTestSources())

        assertEquals(
            "myrelib tests must be extractable with the framework; " +
                "use local fixtures instead of $seasonPackage:\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders
        )
    }

    @Test
    fun `myrelib holds no opmodes`() {
        val offenders = myrelibSources().filter { file ->
            file.readText().contains("@TeleOp") || file.readText().contains("@Autonomous")
        }.map { it.name }

        assertEquals(
            "opmodes belong in the season's package, not the library: $offenders",
            emptyList<String>(),
            offenders
        )
    }
}
