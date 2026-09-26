package synchronice.android

import java.net.URI

internal data class Trecho(
    val type: String,
    val text: String,
    val kind: String? = null,
    val href: String? = null,
    val copyPlain: String? = null,
    val copyFormatted: String? = null,
)

private val ddds = """
    11 12 13 14 15 16 17 18 19 21 22 24 27 28
    31 32 33 34 35 37 38 41 42 43 44 45 46 47 48 49 51 53 54 55
    61 62 63 64 65 66 67 68 69 71 73 74 75 77 79
    81 82 83 84 85 86 87 88 89 91 92 93 94 95 96 97 98 99
""".trim().split(Regex("\\s+")).toSet()
private const val emailAtom = """[A-Z0-9!#$%&'*+/=?^_\x60{|}~-]+"""
private const val emailSource = """$emailAtom(?:\.$emailAtom)*@[A-Z0-9](?:[A-Z0-9-]*[A-Z0-9])?(?:\.[A-Z0-9](?:[A-Z0-9-]*[A-Z0-9])?)+"""
private const val uuidSource = """[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"""
private val email = Regex(emailSource, RegexOption.IGNORE_CASE)
private val uuid = Regex(uuidSource, RegexOption.IGNORE_CASE)
private val candidatos = Regex(
    """(?<![\p{L}\d])(?:(https?://[^\s<>"']+)|(?<!\.)(?:$emailSource)|(?:$uuidSource|\d{2}\.\d{3}\.\d{3}/\d{4}-\d{2}|\d{3}\.\d{3}\.\d{3}-\d{2}|(?:\+?55[ \t]*)?(?:\(\d{2}\)|\d{2})[ \t.-]*\d{4,5}[ \t.-]*\d{4}|\d{14})(?![\p{L}\d]))""",
    RegexOption.IGNORE_CASE,
)

internal fun segmentarTexto(texto: String): List<Trecho> {
    val trechos = mutableListOf<Trecho>()
    var cursor = 0
    for (match in candidatos.findAll(texto)) {
        val trecho = if (match.groups[1] != null) criarLink(match.value) else criarCopiavel(match.value)
        if (trecho == null) continue
        val inicio = match.range.first
        if (inicio > cursor) trechos.add(Trecho("text", texto.substring(cursor, inicio)))
        trechos.add(trecho)
        cursor = if (trecho.type == "link") inicio + trecho.text.length else match.range.last + 1
    }
    if (cursor < texto.length) trechos.add(Trecho("text", texto.substring(cursor)))
    return trechos.ifEmpty { listOf(Trecho("text", texto)) }
}

private fun criarLink(bruto: String): Trecho? {
    var url = bruto.trimEnd('.', ',', ';', ':', '!', '?')
    for ((opening, closing) in listOf('(' to ')', '[' to ']', '{' to '}')) {
        while (url.endsWith(closing) && url.count { it == closing } > url.count { it == opening }) {
            url = url.dropLast(1)
        }
    }
    val uri = try { URI(url) } catch (_: java.net.URISyntaxException) { return null }
    if (uri.host.isNullOrEmpty() || uri.scheme.lowercase() !in listOf("http", "https")) return null
    return Trecho("link", url, href = url)
}

private fun copiavel(kind: String, text: String, plain: String = text) =
    Trecho("copyable", text, kind = kind, copyPlain = plain, copyFormatted = text)

private fun criarCopiavel(bruto: String): Trecho? {
    if (email.matches(bruto)) return copiavel("email", bruto)
    if (uuid.matches(bruto)) return copiavel("pix", bruto)
    val digits = bruto.filter { it in '0'..'9' }
    val phone = if (digits.length in 12..13 && digits.startsWith("55")) digits.drop(2) else digits
    val ddd = phone.take(2)
    if (ddd in ddds) {
        if (phone.length == 11 && phone[2] == '9') {
            return copiavel("mobile", "($ddd) ${phone.substring(2, 7)}-${phone.substring(7)}", phone)
        }
        if (phone.length == 10 && phone[2] in '2'..'5') {
            return copiavel("landline", "($ddd) ${phone.substring(2, 6)}-${phone.substring(6)}", phone)
        }
    }
    if (digits.toSet().size == 1) return null
    if (digits.length == 11) {
        fun check(count: Int): Int {
            val remainder = ((0 until count).sumOf { digits[it].digitToInt() * (count + 1 - it) } * 10) % 11
            return if (remainder == 10) 0 else remainder
        }
        if (check(9) == digits[9].digitToInt() && check(10) == digits[10].digitToInt()) {
            return copiavel("cpf", "${digits.take(3)}.${digits.substring(3, 6)}.${digits.substring(6, 9)}-${digits.drop(9)}", digits)
        }
    }
    if (digits.length == 14) {
        fun check(count: Int): Int {
            val remainder = (0 until count).sumOf { digits[it].digitToInt() * ((count - 1 - it) % 8 + 2) } % 11
            return if (remainder < 2) 0 else 11 - remainder
        }
        if (check(12) == digits[12].digitToInt() && check(13) == digits[13].digitToInt()) {
            return copiavel("cnpj", "${digits.take(2)}.${digits.substring(2, 5)}.${digits.substring(5, 8)}/${digits.substring(8, 12)}-${digits.drop(12)}", digits)
        }
    }
    return null
}
