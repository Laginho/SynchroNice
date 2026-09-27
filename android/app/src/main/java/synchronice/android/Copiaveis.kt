package synchronice.android

import java.io.ByteArrayOutputStream
import java.math.BigInteger

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
// O \s do JavaScript (flag u) também pega estes; o do Java, não.
private const val jsSpaces = """\s\x{a0}\x{1680}\x{2000}-\x{200a}\x{2028}\x{2029}\x{202f}\x{205f}\x{3000}\x{feff}"""
private val email = Regex(emailSource, RegexOption.IGNORE_CASE)
private val uuid = Regex(uuidSource, RegexOption.IGNORE_CASE)
private val candidatos = Regex(
    """(?<![\p{L}\d])(?:(https?://[^$jsSpaces<>"']+)|(?<!\.)(?:$emailSource)|(?:$uuidSource|\d{2}\.\d{3}\.\d{3}/\d{4}-\d{2}|\d{3}\.\d{3}\.\d{3}-\d{2}|(?:\+?55[ \t]*)?(?:\(\d{2}\)|\d{2})[ \t.-]*\d{4,5}[ \t.-]*\d{4}|\d{14})(?![\p{L}\d]))""",
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
    return if (isWebLink(url)) Trecho("link", url, href = url) else null
}

/**
 * Decide "é link" como o `new URL()` do PC (WHATWG) para http(s): caminho, query e fragmento valem
 * como vierem; recusa host vazio ou com caractere proibido, IPv4/IPv6 inválido e porta que não seja
 * número até 65535. O java.net.URI de antes recusava `|`, `{}`, `^` e crase no caminho e `_` ou
 * acento no host, que o PC aceita.
 * ponytail: sem IDNA de verdade; um rótulo punycode inválido (`xn--`) vira link aqui e não no PC.
 */
private fun isWebLink(url: String): Boolean {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0 || url.substring(0, schemeEnd).lowercase() !in setOf("http", "https")) return false
    val rest = url.substring(schemeEnd + 3)
    val authorityEnd = rest.indexOfAny(charArrayOf('/', '?', '#', '\\'))
    val hostAndPort = rest.substring(0, if (authorityEnd < 0) rest.length else authorityEnd).substringAfterLast('@')
    val port: String
    if (hostAndPort.startsWith('[')) {
        val close = hostAndPort.indexOf(']')
        if (close < 0 || !isIpv6(hostAndPort.substring(1, close))) return false
        val after = hostAndPort.substring(close + 1)
        if (after.isNotEmpty() && !after.startsWith(':')) return false
        port = after.removePrefix(":")
    } else {
        val host = percentDecode(hostAndPort.substringBefore(':')) ?: return false
        if (host.isEmpty() || forbiddenHost.containsMatchIn(host)) return false
        if (endsInNumber(host) && !isIpv4(host)) return false
        port = hostAndPort.substringAfter(':', "")
    }
    return port.all { it in '0'..'9' } && port.trimStart('0').let { it.length <= 5 && (it.toIntOrNull() ?: 0) <= 65535 }
}

private val forbiddenHost = Regex("""[\x{0}-\x{20}#%/:<>?@\[\\\]^|\x{7f}]""")

/** Bytes `%XX` viram UTF-8; sequência inválida recusa o host, como o IDNA do PC. */
private fun percentDecode(value: String): String? {
    if ('%' !in value) return value
    val bytes = ByteArrayOutputStream()
    var i = 0
    while (i < value.length) {
        val hex = value.substring(minOf(i + 1, value.length), minOf(i + 3, value.length))
        if (value[i] == '%' && hex.length == 2 && hex.all { it.isAsciiHex() }) {
            bytes.write(hex.toInt(16))
            i += 3
        } else {
            bytes.write(value[i].toString().toByteArray(Charsets.UTF_8))
            i++
        }
    }
    return bytes.toByteArray().toString(Charsets.UTF_8).takeUnless { it.contains(Char(0xfffd)) }
}

private fun Char.isAsciiHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun hostLabels(host: String): List<String> =
    host.split('.').let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }

private fun endsInNumber(host: String): Boolean {
    val last = hostLabels(host).last()
    return (last.isNotEmpty() && last.all { it in '0'..'9' }) ||
        (last.startsWith("0x", ignoreCase = true) && last.drop(2).all { it.isAsciiHex() })
}

/** O IPv4 do WHATWG: até 4 partes, decimal, `0x` hex ou octal com zero à esquerda (`127.1` vale). */
private fun isIpv4(host: String): Boolean {
    val parts = hostLabels(host)
    if (parts.size > 4 || parts.any { it.isEmpty() }) return false
    val numbers = parts.map { part ->
        val (digits, radix) = when {
            part.startsWith("0x", ignoreCase = true) -> part.drop(2) to 16
            part.length > 1 && part.startsWith('0') -> part.drop(1) to 8
            else -> part to 10
        }
        if (digits.isEmpty()) BigInteger.ZERO else digits.toBigIntegerOrNull(radix) ?: return false
    }
    if (numbers.dropLast(1).any { it > BigInteger.valueOf(255) }) return false
    return numbers.last() < BigInteger.valueOf(256).pow(5 - numbers.size)
}

private fun isIpv6(address: String): Boolean {
    if (address.isEmpty() || address.any { !it.isAsciiHex() && it != ':' && it != '.' }) return false
    val halves = address.split("::")
    if (halves.size > 2) return false
    val groups = halves.flatMap { if (it.isEmpty()) emptyList() else it.split(':') }
    var count = 0
    for ((index, group) in groups.withIndex()) {
        count += if (index == groups.lastIndex && '.' in group) {
            val octets = group.split('.')
            val valid = octets.size == 4 && octets.all { octet ->
                octet.length in 1..3 && octet.all { it in '0'..'9' } && !(octet.length > 1 && octet[0] == '0') && octet.toInt() <= 255
            }
            if (!valid) return false
            2
        } else {
            if (group.length !in 1..4 || '.' in group) return false
            1
        }
    }
    return if (halves.size == 2) count <= 7 else count == 8
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
