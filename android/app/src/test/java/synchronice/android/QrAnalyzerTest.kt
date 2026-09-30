package synchronice.android

import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QrAnalyzerTest {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }

    private val json = """{"instance_name":"synchronice-12345678","port":47820,"token":"0123456789abcdef0123456789abcdef"}"""

    // Quadro em luminância: preto (0) nos módulos, branco (255) no resto, com margem.
    private fun frameWithQr(rotate90: Boolean): Triple<ByteArray, Int, Int> {
        val size = 300
        val matrix = QRCodeWriter().encode(json, BarcodeFormat.QR_CODE, size, size)
        val pixels = ByteArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val black = if (rotate90) matrix.get(y, size - 1 - x) else matrix.get(x, y)
                pixels[y * size + x] = if (black) 0 else 255.toByte()
            }
        }
        return Triple(pixels, size, size)
    }

    @Test
    fun `quadro sem QR devolve null sem lancar`() {
        val gray = ByteArray(200 * 200) { 128.toByte() }
        assertNull(decodeLuminance(reader, gray, 200, 200))
    }

    @Test
    fun `QR girado 90 graus dentro do quadro e lido`() {
        val (pixels, width, height) = frameWithQr(rotate90 = true)
        assertEquals(json, decodeLuminance(reader, pixels, width, height))
    }

    @Test
    fun `QR na orientacao normal e lido`() {
        val (pixels, width, height) = frameWithQr(rotate90 = false)
        assertEquals(json, decodeLuminance(reader, pixels, width, height))
    }
}
