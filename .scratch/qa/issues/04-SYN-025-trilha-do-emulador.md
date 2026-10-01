# SYN-025: Trilha do emulador — PC isolado, `up`/`down` e câmera com QR
Stage: done
Status: ready-for-agent
Blocked by: SYN-023
Review: agent

- Primary files:
  - pc/src-tauri/src/config.rs (função pura que resolve o diretório e o autostart)
  - pc/src-tauri/src/lib.rs (só os dois `app_config_dir()` e o registro do autostart)
  - pc/src-tauri/tests/config.rs
  - New: qa/up.ps1, qa/down.ps1
  - qa/pc.ps1 (modo isolado)
  - docs/agents/qa.md (seção do emulador)

#### What to build

Spec: `.scratch/qa/spec.md`, histórias 5–7, 14 e 34, e a seção
"Implementation Decisions" (Isolamento do PC no emulador, Emulador,
Preparação idempotente).

O kit roda o app inteiro sem aparelho e sem tocar no Par real.

- **PC.**
  - Com `SYNCHRONICE_DATA_DIR` definida:
    - toda a configuração vive nesse diretório;
    - o registro no autostart não acontece.
  - Uma função pura resolve o diretório e diz se o autostart vale. Os dois
    lugares que pedem `app_config_dir()` passam por ela.
- **`up`** é idempotente e faz, nesta ordem:
  1. Sobe o `SynchroNice_API37`, se ele ainda não estiver de pé:
     - `-no-window -no-audio -no-snapshot-save -gpu swiftshader_indirect`;
     - `-camera-back imagefile:<png do QR>`.
  2. Espera o `sys.boot_completed`.
  3. Compila e instala o APK de debug da branch atual. Em
     `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, desinstala e instala de novo.
  4. Compila o PC de debug (`npx tauri build --debug --no-bundle`).
  5. Anota se a instância real estava rodando e a mata.
  6. Abre o PC isolado, com CDP:
     - `SYNCHRONICE_DATA_DIR` e `WEBVIEW2_USER_DATA_FOLDER` num diretório
       da execução;
     - Pasta semeada no `config.json` isolado.
  7. Pareia o emulador pelo `pair` do SYN-023, com o IP de LAN do PC.
- **`down`:**
  1. derruba o emulador e o PC isolado;
  2. reabre o `release\pc.exe` real, se ele estava rodando antes do `up`.
- **Spike da câmera.** Verificar se a CameraX do app lê o QR mostrado pelo
  `imagefile:`, e registrar o resultado em `## Comments`:
  - se ler, o B.2 é verificável no emulador;
  - se não, o B.2 fica 👤 no emulador e o `qa.md` diz isso.

#### Acceptance criteria

1. A função pura, com a variável definida, devolve o caminho dela e
   "autostart desligado". Sem a variável, devolve o diretório padrão e
   "autostart ligado".
2. O PC aberto com a variável grava o `config.json`, o outbox e o
   `accepted-file-ids.txt` só no diretório dela. O valor do autostart no
   registro não muda.
3. `up` duas vezes seguidas não reinicia o emulador, que já está de pé. Os
   dois `up` terminam com o emulador pareado e "Na rede".
4. Depois de `up`, o passo C.1 do walkthrough roda pelo kit no emulador. O
   hash do `config.json` real, em `%APPDATA%\dev.synchronice.pc`, é o mesmo de
   antes do `up`.
5. `down` deixa nenhum `qemu` e nenhum PC isolado rodando. Ele reabre o
   `release\pc.exe` se, e só se, ele estava rodando antes.
6. O resultado do spike da câmera está em `## Comments`, e o `qa.md`
   reflete o resultado.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1 -TestsOnly
    powershell -ExecutionPolicy Bypass -File .\qa\up.ps1
    powershell -ExecutionPolicy Bypass -File .\qa\down.ps1
    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Tests stage 2 writes (own commit, red)

- `pc/src-tauri/tests/config.rs`: com e sem a variável, o diretório resolvido
  e o autostart. Vermelho porque a função não existe.

## Comments

- Stage 2: seam confirmada: `config::resolve_data_dir`, recebendo o caminho
  padrão e o valor opcional de `SYNCHRONICE_DATA_DIR`, devolve caminho e
  autorização de autostart. Teste sem alterar variáveis globais do processo.
  Os chamadores são `start_server`, `mudar_pasta` e o registro no setup;
  Par, outbox e accepted-file-ids derivam do mesmo diretório em start_server.
  Configuração ausente continua seguindo o comportamento padrão existente.

- Vermelho: commit de testes `40851a2`; `gate.ps1 -TestsOnly` saiu 1 por
  E0425: `config::resolve_data_dir` ainda não existia. Nenhum commit de
  implementação alterou testes. Log local: `syn-025/red.log` em qa-runs.
- Primeira rodada verde do teste novo: 7 testes de config passaram. Um teste
  existente de servidor (`reenvio_de_arquivo_com_o_mesmo_id_nao_duplica_item_nem_arquivo`)
  falhou na conexão por erro Windows 10053; servidor não foi alterado.
  A rodada do gate completo será a confirmação final de toda a suíte.
- Critérios 2 e 4: POST de arquivo no servidor isolado devolveu 201 e gravou
  `data/Pasta/synchronice-qa-syn025.txt` e `data/accepted-file-ids.txt`
  (`syn025-isolated`). `send_text` pela API Tauri gerou pendente
  `QA isolated outbox` em `data/outbox/manifest.json`. Configuração real
  manteve SHA-256 `90D3D7B3C5503FACA4BBC387B7178E35A087B7F8C4FF455135B9A6FB5C668993`;
  valor `SynchroNice` em HKCU/Run permaneceu idêntico ao snapshot anterior.
- C.1 pelo kit: DOM `QA SYN-025 C1`, dump com texto igual e
  `bubble-1-incoming-delivered`, indicador `Na rede`; HEAD /listen 200.
  Evidência local: `syn-025/c1.xml`. Foi necessário instalar a dependência
  existente com `npm ci --prefix qa`, conforme o pré-requisito do kit.
- Critérios 3 e 5: `up3` e `up5` terminaram online, saída 0, reutilizando
  qemu PID 13752. `up4` foi interrompido pelo harness externo que tratou um
  aviso de stderr do JVM como fatal; corrigido no harness, sem mudar testes.
  Com release previamente aberto, `up-real1` e `up-real2` consecutivos
  também saíram 0 e mantiveram o PID do qemu, com `restoredOnDown=true`.
  `down` deixou zero qemu/PC debug e reabriu o release (PID 23732 confirmado).
  Com release inicialmente fechado, `down` deixou zero qemu e zero PC,
  `restored=false`. Os logs ficam em `.scratch/qa-runs/syn-025/`.
- Spike da câmera: **não leu; B.2 é 👤 no emulador**. Após limpar somente
  os dados do app no AVD e conceder CAMERA, a CameraX mostrou o PNG com QR,
  mas o enquadramento cortou parte do código. Uma segunda tentativa com QR
  menor e margem branca continuou cortando-o; duas leituras mantiveram
  scanner e presença 204. Prints locais `scanner.png` e
  `scanner-padded.png`, dumps `camera-padded.xml`/`camera-padded-retry.xml`.
  `qa.md` registra a limitação; pareamento das execuções usa SYN-023.
- O lançamento foi ajustado para não herdar pipes nativos do chamador:
  wrappers devolvem controle enquanto os apps permanecem vivos, com logs
  redirecionados. As variáveis de ambiente do chamador são restauradas.
- Handoff stage 2: gate completo em primeiro plano, saída 0, `gate: passou`
  e `BUILD SUCCESSFUL`. Fmt, clippy, typecheck e lint verdes. Rust 52,
  UI 47 e QA 14 passaram; Android app 59 e protocol-client 62 UP-TO-DATE,
  XMLs com zero falhas, erros ou skips. Log: `syn-025/gate-final.log`.
  O gate anterior passou todos os testes, mas pediu formatação da última
  asserção: corrigida no commit **somente de teste** `f60f9ad`, sem alterar
  lógica ou contrato: o diff desse commit contém só a quebra da linha da
  asserção, e o conteúdo sem whitespace é idêntico ao teste vermelho.
  Nenhum commit de código toca testes.
- Branch `syn-025`, base `sweatshop/2026-09-30-2146`. Config real e
  autostart preservados; instância release aberta só para verificar a
  restauração foi fechada depois, devolvendo a máquina ao estado inicial
  (nenhum PC nem qemu). Pronto para stage 3; B.2 mantém a limitação do spike
  registrada, conforme o critério 6.

#### Resolution (2026-10-01)

Verdict: Approve

- Revisão de `6afa51e...b0cd98c`, após rebase sobre
  `sweatshop/2026-09-30-2146`; integração sem squash em `c782ef9`.
  Arquivos: `config.rs`, `lib.rs`, `tests/config.rs`, `qa/up.ps1`,
  `qa/down.ps1`, `qa/pc.ps1` e a seção do emulador em `docs/agents/qa.md`.
  Stage 3 não precisou alterar código nem testes.
- ✅ Critério 1: função pura testada com e sem override; 7 testes de config
  verdes. Vermelho confirmado no commit `40851a2` e no log E0425 pela função
  ausente. Os commits de implementação não tocam testes; `f60f9ad` contém
  só formatação, com conteúdo idêntico ao vermelho sem whitespace.
- ✅ Critério 2: arquivo HTTP 201 e recibo `syn025-review-file` em
  `data/Pasta`/`data/accepted-file-ids.txt`; Pendente criado pela API Tauri
  em `data/outbox/manifest.json`. Hashes dos arquivos reais e valor do
  autostart preservados.
- ✅ Critério 3: dois `up` consecutivos com saída 0, o mesmo qemu PID
  `29916`, presença `Na rede` nos dois dumps e `HEAD /listen` 200.
- ✅ Critério 4: C.1 pelo composer do PC; `QA SYN-025 review C1` no DOM
  `.item:not(.pendente)` e em `bubble-1-incoming-delivered` no celular.
  Prints inspecionados. Config real manteve SHA-256
  `90D3D7B3C5503FACA4BBC387B7178E35A087B7F8C4FF455135B9A6FB5C668993`.
- ✅ Critério 5: sem release anterior, `down` deixou zero PC/qemu e não
  abriu release. Com release anterior, `up` registrou restauração e
  `down` deixou zero debug/qemu e um release (PID `30804`). Segundo `down`
  preservou esse PID. O release aberto apenas para esta verificação foi
  fechado depois; estado inicial devolvido, com zero PC/qemu.
- ✅ Critério 6: Comments e `qa.md` registram o spike negativo permitido
  pelo contrato. A imagem `scanner-padded.png` foi inspecionada: o QR está
  cortado. B.2 continua 👤 no emulador; os demais passos usam `pair`.
- Standards: nenhum desvio de Primary files/test-first. Higiene do
  histórico: seis commits sem corpo; o corpo de `f60f9ad` explica o motivo
  sem repetir o ID, embora todos os assuntos o citem. Possível duplicação
  do setup WMI/ambiente em `pc.ps1` e `down.ps1`, aceita para os dois
  consumidores. Nenhum desses apontamentos é critério não atendido,
  violação de seam/test-first ou regressão que reabra o ticket.
- Spec: zero achados contratuais; seis critérios atendidos, sem regressão
  identificada. Também exercitado o launcher compartilhado no modo real.
- Gate completo em primeiro plano, saída 0: fmt, clippy, typecheck e lint
  verdes; Rust **52**, UI **47**, QA **14** passaram. Android app **59** e
  protocol-client **62** UP-TO-DATE, XMLs sem falhas, erros ou skips;
  `BUILD SUCCESSFUL` e `gate: passou`. Log local:
  `.scratch/qa-runs/syn-025-review/gate.log`.
- Relatório e evidências:
  [QA da revisão](../../qa-runs/2026-10-01-025-review.md).
