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
| 2 — os quatro IPv4 inválidos do contrato | ✅ | subteste do gate: exit 1, erro de IPv4 e zero lançamentos para cada caso |
| 3 — config e IPv4 explícito válidos | ✅ | subteste do gate: um lançamento da activity correta, extra `qr`, nome/token/IP iguais à entrada e porta 47820 |
| 4 — IPv4 omitido | ✅ | subteste do gate: IP do QR igual ao lookup UDP local calculado na máquina |
| 5 — respostas `Error:` e `Exception` | ✅ | subteste do gate: exit 1 mesmo com adb falso retornando código 0 |
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

## Gate

`powershell -ExecutionPolicy Bypass -File .\gate.ps1` terminou em primeiro
plano com exit 0 e `gate: passou`:

- Rust: 83 testes passaram (8 de unidade, 7 de config, 5 de outbox e 63 de
  servidor), sem falhas; fmt e clippy passaram.
- PC/UI: typecheck passou; 61 testes passaram, sem falhas.
- QA: 20 testes passaram, sem falhas. O `pair` tem um pai e cinco subtestes,
  21 execuções do wrapper; todos passaram porque ainda faltam os seis casos
  indevidos reproduzidos nesta revisão.
- Android: `BUILD SUCCESSFUL`, 41 tasks, 1 executada e 40 up-to-date;
  as tarefas de teste estavam up-to-date.

## Tentativa do emulador

As duas chamadas de `up` aguardaram seu resultado em primeiro plano:

1. `powershell -ExecutionPolicy Bypass -File .\qa\up.ps1`: exit 1,
   `Program 'adb.exe' failed to run: The system cannot find the file specified`.
   A inspeção do SDK sob `%LOCALAPPDATA%` encontrou um erro de mount point.
2. `powershell -ExecutionPolicy Bypass -File .\qa\up.ps1 -Sdk D:\AppData\Local\Android\Sdk`:
   exit 1, `infraestrutura: portas do emulador 5554/5555 ocupadas`.
   O caminho direto vem de `android/local.properties`.

Ambas abortaram antes dos builds e antes de instalar APK ou abrir/parear o
PC isolado. Assim, o transporte nativo PowerShell/adb do QR não foi
verificado nesta revisão. É falha de infraestrutura, não falha adicional
do contrato. Os critérios executáveis usam o fake `.ps1` aprovado pelo
ticket; o critério 1 reabre pelos defeitos reproduzidos acima.

Cada `up` chamou `down` no erro. A chamada final
`powershell -ExecutionPolicy Bypass -File .\qa\down.ps1 -Sdk D:\AppData\Local\Android\Sdk`
terminou com exit 0 e `{"stopped":true,"restored":false}`. O estado em
`.scratch/qa-runs/emulator/emulator.json` está inativo, com serial, endpoint
e PID do PC nulos e `realWasRunning=false`.

Limpeza: somente os três arquivos temporários sintéticos e seu diretório
foram removidos. Nenhum arquivo de usuário foi criado ou apagado. Os dados
e artefatos isolados gerados pelo `up` ficam no diretório local ignorado;
nenhum arquivo foi empurrado para o celular ou recebido na Pasta.
