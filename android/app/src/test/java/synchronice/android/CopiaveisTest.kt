package synchronice.android

import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CopiaveisTest {
    @Test
    fun `segmentacao corresponde aos mesmos casos usados pelo PC`() {
        val json = javaClass.getResource("/copiaveis.json")!!.readText()
        val cases = JSONObject(json).getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val expected = case.getJSONArray("expected")
            val trechos = (0 until expected.length()).map { position ->
                val trecho = expected.getJSONObject(position)
                Trecho(
                    type = trecho.getString("type"),
                    text = trecho.getString("text"),
                    kind = if (trecho.has("kind")) trecho.getString("kind") else null,
                    href = if (trecho.has("href")) trecho.getString("href") else null,
                    copyPlain = if (trecho.has("copyPlain")) trecho.getString("copyPlain") else null,
                    copyFormatted = if (trecho.has("copyFormatted")) trecho.getString("copyFormatted") else null,
                )
            }
            assertEquals(trechos, segmentarTexto(case.getString("input")), case.getString("name"))
        }
    }
}
