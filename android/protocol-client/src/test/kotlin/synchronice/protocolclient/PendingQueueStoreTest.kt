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
    fun `exclusao enfileirada sobrevive a reabrir o store, inclusive com uma lista vazia`() {
        val dir = createTempDir(prefix = "synchronice-delete-")
        try {
            val queueFile = File(dir, "pending.txt")
            val store = FilePendingQueueStore(queueFile)
            val both = PendingItem.Delete(listOf(1L, 22L), listOf("a-b", "c"))
            val onlyPc = PendingItem.Delete(listOf(3L), emptyList())
            val onlyPhone = PendingItem.Delete(emptyList(), listOf("d"))
            store.enqueue(PendingItem.Text("antes"))
            store.enqueue(both)
            store.enqueue(onlyPc)
            store.enqueue(onlyPhone)

            assertEquals(
                listOf(PendingItem.Text("antes", store.load()[0].id), both, onlyPc, onlyPhone),
                FilePendingQueueStore(queueFile).load(),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `remover por id persiste, apaga copias internas e preserva o resto na ordem`() {
        val dir = createTempDir(prefix = "synchronice-remove-")
        try {
            val queueFile = File(dir, "pending.txt")
            val original = File(dir, "original.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val store = FilePendingQueueStore(queueFile)
            val text = PendingItem.Text("texto")
            val keptText = PendingItem.Text("fica")
            val delete = PendingItem.Delete(listOf(9L), listOf("z"))
            store.enqueue(text)
            val file = store.enqueue(PendingItem.File(original.path, original.name)) as PendingItem.File
            store.enqueue(keptText)
            store.enqueue(delete)
            assertTrue(File(file.path).exists())

            store.remove(setOf(text.id, file.id, "id-que-nao-existe"))

            assertEquals(listOf(keptText, delete), store.load())
            assertEquals(listOf(keptText, delete), FilePendingQueueStore(queueFile).load())
            assertFalse(File(file.path).exists())
            assertTrue(File(dir, "pending.txt.payloads").listFiles().isNullOrEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

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
