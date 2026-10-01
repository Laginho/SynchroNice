# SYN-023: Atalho de pareamento no build de debug
Stage: done
Status: ready-for-agent
Blocked by: SYN-022
Review: agent

- Primary files:
  - New: android/app/src/debug/AndroidManifest.xml
  - New: android/app/src/debug/java/synchronice/android/DebugPairActivity.kt
  - New: android/app/src/testDebug/java/synchronice/android/DebugPairActivityTest.kt
  - qa/phone.ps1 (primitiva `pair`)
  - docs/agents/qa.md (seção da primitiva)

#### What to build

Spec: `.scratch/qa/spec.md`, história 24, e a seção "Implementation
Decisions" (Ganchos no app Android, Redmi usa a instância real).

O kit pareia o celular com o PC sem câmera. Uma activity que só existe no
build de debug recebe o JSON do QR num extra de string, chama `acceptQr` do
`AppState` da `Application`, abre o chat e termina. A primitiva `pair` do kit
monta esse JSON a partir do `config.json` do PC:
`instance_name`, `port` 47820, `token` e o `ip` da rota padrão, que um
argumento pode sobrescrever. Depois ela dispara a activity com `am start`.

No Redmi, isso cobre os passos de Desparear e parear de novo (I.2–I.4). A
primeira leitura (B.2) continua pela câmera real.

#### Acceptance criteria

1. Depois do intent com um JSON de QR válido no extra, o Par está gravado e o
   `last-address` tem o `ip` do JSON. A activity termina e abre o chat.
2. Com um JSON inválido, ou sem o extra, nenhum Par é gravado, a activity
   termina e nada quebra.
3. A activity existe só no source set de debug. O manifest de release, depois
   do merge, não a declara.
4. No Redmi, depois de Desparear pela engrenagem, `pair` leva o app ao chat.
   A presença aparece "Na rede" no dump, e o `HEAD /listen` devolve 200.
5. `docs/agents/qa.md` documenta a primitiva `pair` e diz que ela só funciona
   com o APK de debug.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1 -TestsOnly
    powershell -ExecutionPolicy Bypass -File .\qa\phone.ps1 pair
    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Sem o Redmi alcançável, o critério 4 vira `Débito humano:`.

## Tests stage 2 writes (own commit, red)

- `DebugPairActivityTest` (Robolectric, no molde do `ShareActivityTest` e do
  `AcceptQrIpTest`): o intent com JSON válido grava o Par e o `last-address`;
  o intent inválido ou sem extra não grava. Vermelho porque a
  `DebugPairActivity` não existe.

## Comments

- Stage 2: seam confirmada pelo ticket: intent de `DebugPairActivity`, extra
  string `qr`, observando o AppState da Application, persistência após reabrir
  AppState, intent do chat e término da activity. Casos: QR válido, inválido e
  extra ausente. `acceptQr` já valida o JSON e é chamado pelo scanner em
  `MainActivity` e pelos testes de pareamento, entrega e presença; não será
  alterado. A primitiva nova passa pelo mesmo `Adb-Run` das demais ações;
  os guardas de transporte sem fio e tela desbloqueada continuam aplicáveis.
- Vermelho: testes committed em `4d12967`; `gate.ps1 -TestsOnly` saiu 1
  em `:app:compileDebugUnitTestKotlin` por `Unresolved reference
  'DebugPairActivity'`, ausente como previsto. Rust 51, UI 47 e parser QA 5
  passaram; protocol-client estava UP-TO-DATE.
- Verde: `gate.ps1 -TestsOnly` saiu 0, `gate: passou` e `BUILD SUCCESSFUL`.
  Os três testes novos passaram (QR válido, inválido e extra ausente), sem
  alterar testes nos commits de implementação. A activity usa o AppState da
  Application, grava pelo `acceptQr`, abre MainActivity e termina.
- Verificação da primitiva: harness temporário com adb sintético executou
  o wrapper real com config no formato do PC (`nome_instancia` e `token`),
  conferiu `--es qr`, JSON completo, porta 47820, IP explícito e rota padrão.
  Config inválido e IPv4 inválido saíram 1 sem disparar a activity. O harness
  foi removido. Um roundtrip de JSON sintético pelo shell adb real sem fio
  confirmou a preservação das aspas no Windows PowerShell. Nenhum token real
  foi exibido ou incluído no ticket.
- Verificação física: `powershell -ExecutionPolicy Bypass -File
  .\qa\phone.ps1 pair` reconectou o Redmi sem fio, mas saiu 1 na preparação
  com `SecurityException: Permission denial ... WRITE_SECURE_SETTINGS`,
  antes de ler o config ou disparar a activity. Mesma indisponibilidade de
  infraestrutura registrada no SYN-022; nenhuma configuração de segurança
  foi alterada e o Par real foi preservado.
- Débito humano: SYN-023 critério 4 — Desparear pela engrenagem, executar
  `pair` com APK de debug, ler `Na rede` no dump e confirmar `HEAD /listen`
  200 no Redmi. O shell nega WRITE_SECURE_SETTINGS, impedindo a preparação
  do kit; não houve execução física do pareamento nem evidência de presença.
- Critério 3: `gradlew.bat :app:processReleaseMainManifest
  :app:processReleaseManifest` terminou com `BUILD SUCCESSFUL`; leitura dos
  manifests merged confirmou DebugPairActivity presente em debug e ausente
  em release. Fonte e declaração ficam exclusivamente em `src/debug`.
- Handoff stage 2: gate completo em primeiro plano, saída 0, `gate: passou`
  e `BUILD SUCCESSFUL`. Fmt, clippy, typecheck e lint passaram. Rust: 51;
  UI: 47; parser QA: 5; Android app: 59; protocol-client: 62. Os XMLs Android
  confirmam zero falhas, erros ou skips; no gate final ambos UP-TO-DATE,
  após execução verde real dos 59 testes do app no TestsOnly.
  Testes ficam apenas no commit `4d12967`; nenhum commit posterior os alterou.
  Branch `syn-023`, base `sweatshop/2026-09-30-2146`; pronto para stage 3.

#### Resolution (2026-09-30)

Verdict: Approve

- Standards: 1 limpeza não bloqueante, registrada no CLEAN-018 para triagem:
  preservar uma verificação executável da primitiva `pair`. Não consta dos
  critérios deste ticket; nenhum critério, limite de Primary files, regra
  test-first ou comportamento anterior foi violado. Sem smells relevantes.
- Spec: 0 achados. Critérios 1, 2, 3 e 5 atendidos; o critério 4 mantém o
  débito autorizado no Verification, sem afirmar sucesso físico.
- Arquivos revisados: manifest e `DebugPairActivity` em `src/debug`,
  `DebugPairActivityTest` em `src/testDebug`, `qa/phone.ps1` e
  `docs/agents/qa.md`. Nenhuma correção de código nesta revisão.
- Prova vermelho/verde: commit `4d12967` contém somente testes e metadados
  do ticket. O log original registra `gate.ps1 -TestsOnly` saindo 1 por
  `Unresolved reference 'DebugPairActivity'`. Os commits de implementação
  não alteram testes; os XMLs registram os três casos verdes. Manifests
  merged inspecionados: activity presente em debug e ausente em release.
- Gate completo após rebase, em primeiro plano: saída 0, `gate: passou`,
  `BUILD SUCCESSFUL`. Rust 51, UI 47 e parser QA 5 passaram; Android app 59
  e protocol-client 62 UP-TO-DATE, XMLs sem falhas, erros ou skips.
- `phone.ps1 pair` foi repetido: saída 1 por `WRITE_SECURE_SETTINGS` antes
  da leitura do config e do disparo da activity. Computer Use inicializado
  e janelas consultadas; nenhuma janela do celular acessível. O débito do
  critério 4 permanece. Nenhum Par foi alterado nesta revisão.
- Evidência: `.scratch/qa-runs/2026-09-30-redmi-SYN-023-review.md`.
  Merge sem squash `96d7d7e` em `sweatshop/2026-09-30-2146`; Stage e ledger
  fechados juntos no commit desta resolução.
