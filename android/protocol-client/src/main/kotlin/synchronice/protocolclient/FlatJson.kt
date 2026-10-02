package synchronice.protocolclient

/**
 * Parser mínimo para o JSON plano que o PC manda em `/listen` (spec,
 * `server.rs`): um objeto de um nível, chaves e valores string ou número,
 * sem aninhamento nem listas. Não é um parser de JSON geral.
 */
@Suppress("UNCHECKED_CAST")
internal fun parseFlatJsonObject(json: String): Map<String, String> =
    parseJsonObject(json, allowLists = false) as Map<String, String>

/** Como [parseFlatJsonObject], mas um valor pode ser uma lista de strings ou números (`do_pc`, `do_celular`). */
internal fun parseListenObject(json: String): Map<String, Any> = parseJsonObject(json, allowLists = true)

private fun parseJsonObject(json: String, allowLists: Boolean): Map<String, Any> {
    val result = LinkedHashMap<String, Any>()
    var i = skipWhitespace(json, 0)
    require(i < json.length && json[i] == '{') { "esperava '{': $json" }
    i = skipWhitespace(json, i + 1)
    if (i < json.length && json[i] == '}') {
        require(skipWhitespace(json, i + 1) == json.length) { "conteúdo depois de '}': $json" }
        return result
    }
    while (true) {
        i = skipWhitespace(json, i)
        val (key, afterKey) = parseJsonString(json, i)
        i = skipWhitespace(json, afterKey)
        require(json[i] == ':') { "esperava ':': $json" }
        i = skipWhitespace(json, i + 1)
        val (value, afterValue) = when {
            json[i] == '"' -> parseJsonString(json, i)
            allowLists && json[i] == '[' -> parseJsonList(json, i)
            else -> parseJsonNumber(json, i)
        }
        require(key !in result) { "chave JSON duplicada: $key" }
        result[key] = value
        i = skipWhitespace(json, afterValue)
        when (json[i]) {
            ',' -> i = skipWhitespace(json, i + 1)
            '}' -> {
                require(skipWhitespace(json, i + 1) == json.length) { "conteúdo depois de '}': $json" }
                return result
            }
            else -> error("esperava ',' ou '}': $json")
        }
    }
}

private val jsonNumber = Regex("""-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?""")

private fun parseJsonString(json: String, start: Int): Pair<String, Int> {
    require(json[start] == '"') { "esperava '\"': $json" }
    val sb = StringBuilder()
    var i = start + 1
    while (json[i] != '"') {
        if (json[i] == '\\') {
            i++
            when (val escaped = json[i]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                'b' -> sb.append('\b')
                'f' -> sb.append('')
                'u' -> {
                    // toInt(16) aceita sinal ("\u-001"); JSON exige quatro dígitos hex.
                    val hex = json.substring(i + 1, i + 5)
                    require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "escape JSON inválido" }
                    sb.append(hex.toInt(16).toChar())
                    i += 4
                }
                '"', '\\', '/' -> sb.append(escaped)
                else -> error("escape JSON inválido")
            }
        } else {
            require(json[i] >= ' ') { "caractere de controle JSON sem escape" }
            sb.append(json[i])
        }
        i++
    }
    return sb.toString() to (i + 1)
}

private fun parseJsonList(json: String, start: Int): Pair<List<String>, Int> {
    val values = mutableListOf<String>()
    var i = skipWhitespace(json, start + 1)
    if (json[i] == ']') return values to (i + 1)
    while (true) {
        i = skipWhitespace(json, i)
        val (value, afterValue) = if (json[i] == '"') parseJsonString(json, i) else parseJsonNumber(json, i)
        values.add(value)
        i = skipWhitespace(json, afterValue)
        when (json[i]) {
            ',' -> i++
            ']' -> return values to (i + 1)
            else -> error("esperava ',' ou ']': $json")
        }
    }
}

private fun parseJsonNumber(json: String, start: Int): Pair<String, Int> {
    var i = start
    while (i < json.length && json[i] != ',' && json[i] != '}' && json[i] != ']' && !json[i].isWhitespace()) i++
    val number = json.substring(start, i)
    require(jsonNumber.matches(number)) { "número JSON inválido" }
    return number to i
}

private fun skipWhitespace(json: String, start: Int): Int {
    var i = start
    while (i < json.length && json[i] in " \t\n\r") i++
    return i
}
