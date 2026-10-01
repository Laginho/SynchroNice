# SYN-023: Atalho de pareamento no build de debug
Stage: implementing
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
