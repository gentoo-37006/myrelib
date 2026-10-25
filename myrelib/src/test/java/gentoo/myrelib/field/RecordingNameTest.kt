package gentoo.myrelib.field

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingNameTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `new names contain readable UTC time and remain safely downloadable`() {
        RecordingFiles.attach(temporary.root)
        val file = RecordingFiles.newFile()!!
        assertTrue(file.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2}\\.\\d{3}Z-[a-f0-9-]+\\.jsonl")))
        file.parentFile!!.mkdirs(); file.createNewFile()
        assertEquals(file, RecordingFiles.find(file.name))
        assertNull(RecordingFiles.find("../${file.name}"))
    }

    @Test fun `old epoch names and readable names sort by timestamp together`() {
        RecordingFiles.attach(temporary.root)
        val directory = File(temporary.root, "match-recordings").apply { mkdirs() }
        val old = File(directory, "1000-aa.jsonl").apply { createNewFile() }
        val newer = File(directory, "1970-01-01T00-00-02.000Z-bb.jsonl").apply { createNewFile() }
        assertEquals(listOf(newer, old), RecordingFiles.list())
        assertEquals(old, RecordingFiles.find(old.name))
    }
}
