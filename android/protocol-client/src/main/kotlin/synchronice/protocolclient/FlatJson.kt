package synchronice.protocolclient

/**
 * Parser mínimo para o JSON plano que o PC manda em `/listen` (spec,
 * `server.rs`): um objeto de um nível, chaves e valores string ou número,
 * sem aninhamento nem listas. Não é um parser de JSON geral.
 */
internal fun parseFlatJsonObject(json: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
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
        val (value, afterValue) = if (json[i] == '"') parseJsonString(json, i) else parseJsonNumber(json, i)
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
                    sb.append(json.substring(i + 1, i + 5).toInt(16).toChar())
                    i += 4
                }
                else -> sb.append(escaped)
            }
        } else {
            sb.append(json[i])
        }
        i++
    }
    return sb.toString() to (i + 1)
}

private fun parseJsonNumber(json: String, start: Int): Pair<String, Int> {
    var i = start
    while (i < json.length && (json[i].isDigit() || json[i] == '-' || json[i] == '.')) i++
    return json.substring(start, i) to i
}

private fun skipWhitespace(json: String, start: Int): Int {
    var i = start
    while (i < json.length && json[i].isWhitespace()) i++
    return i
}
