package synchronice.protocolclient

import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingQueueStoreTest {
    @Test
    fun `falha ao excluir copia preserva referencia para repetir descarte`() {
        val dir = createTempDir(prefix = "synchronice-clear-")
        try {
            val queueFile = File(dir, "pending.txt")
            val original = File(dir, "original.bin").apply { writeText("payload") }
            val store = FilePendingQueueStore(queueFile)
            val saved = store.enqueue(PendingItem.File(original.path, original.name)) as PendingItem.File
            val payload = File(saved.path)
            assertTrue(payload.delete())
            assertTrue(payload.mkdir())
            val blocker = File(payload, "locked").apply { writeText("block deletion") }

            assertFailsWith<IOException> { store.clear() }
            assertEquals(listOf(saved), store.load())
            val reopened = FilePendingQueueStore(queueFile)
            assertEquals(listOf(saved), reopened.load())
            assertTrue(blocker.delete())
            reopened.clear()
            assertTrue(FilePendingQueueStore(queueFile).load().isEmpty())
            assertFalse(payload.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `descartar fila persiste vazio e apaga copias internas`() {
        val dir = createTempDir(prefix = "synchronice-clear-")
        try {
            val queueFile = File(dir, "pending.txt")
            val original = File(dir, "original.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val store = FilePendingQueueStore(queueFile)
            store.enqueue(PendingItem.Text("texto"))
            val saved = store.enqueue(PendingItem.File(original.path, original.name)) as PendingItem.File
            assertTrue(File(saved.path).exists())

            store.clear()

            assertTrue(store.load().isEmpty())
            assertTrue(FilePendingQueueStore(queueFile).load().isEmpty())
            assertFalse(File(saved.path).exists())
            assertTrue(File(dir, "pending.txt.payloads").listFiles().isNullOrEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `falha ao persistir descarte preserva fila e payload na memoria e no disco`() {
        val dir = createTempDir(prefix = "synchronice-clear-")
        try {
            val queueFile = File(dir, "pending.txt")
            val original = File(dir, "original.bin").apply { writeBytes(byteArrayOf(4, 5)) }
            val store = FilePendingQueueStore(queueFile)
            val saved = store.enqueue(PendingItem.File(original.path, original.name)) as PendingItem.File
            val blocker = File(dir, "pending.txt.tmp").apply { mkdir() }

            assertFailsWith<IOException> { store.clear() }

            assertEquals(listOf(saved), store.load())
            assertEquals(listOf(saved), FilePendingQueueStore(queueFile).load())
            assertTrue(File(saved.path).exists())
            blocker.delete()
        } finally {
            dir.deleteRecursively()
        }
    }
}
