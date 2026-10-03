# QA — 2026-10-03 — CLEAN-018 — revisão stage 3

Branch: `clean-018`, entrega `e5c2206`, base fixa `31e1024`.
Infraestrutura da reprodução: CLI real de `qa/phone.ps1` em Windows
PowerShell 5.1, config sintético e `adb.ps1` falso que captura argv, como
na seam aprovada pelo ticket. Nenhum token real foi lido ou registrado.

| Critério / caso | Veredito | Evidência em texto |
| --- | --- | --- |
| 1 — `nome_instancia: []` | ❌ | exit 0, um `am start`; esperado exit 1 e nenhum lançamento |
| 1 — `nome_instancia: [nome_valido]` | ❌ | exit 0, um `am start`; config exige string, não array |
| 1 — nome válido seguido de LF | ❌ | exit 0, um `am start`; o QR Android exige correspondência integral |
| 1 — `token: []` | ❌ | exit 0, um `am start`; esperado exit 1 e nenhum lançamento |
| 1 — `token: [token_valido]` | ❌ | exit 0, um `am start`; config exige string, não array |
| 1 — token válido seguido de LF | ❌ | exit 0, um `am start`; o QR Android exige correspondência integral |
| Controle — Par válido | ✅ | exit 0 e um `am start` |
| Controle — nome como string inválida | ✅ | exit 1, `config.json sem Par valido`, zero lançamentos |
| Controle — token como string inválida | ✅ | exit 1, `config.json sem Par valido`, zero lançamentos |

O script de reprodução fez nove execuções do wrapper real e terminou com
exit 0 ao confirmar os resultados acima. Ele não alterou código ou testes
do repositório; o diretório temporário foi removido em `finally`.

Os dois formatos indevidos pertencem ao critério 1. Em
`pc/src-tauri/src/config.rs`, `ConfigFile` declara ambos como
`Option<String>`. Em
`android/protocol-client/src/main/kotlin/synchronice/protocolclient/PairingInfo.kt`,
o parser do QR recusa listas e usa `Regex.matches` para os campos.
O wrapper aplica `-cnotmatch` a coleções e usa anchors `$` que aceitam LF
final; os testes entregues não incluem esses casos.

Gate e preparação do emulador: resultados serão acrescentados após a
conclusão das chamadas em primeiro plano.

Limpeza até aqui: somente os três arquivos temporários sintéticos e seu
diretório foram removidos. Nenhum arquivo de usuário foi criado ou apagado.
