# SYN-025: Trilha do emulador — PC isolado, `up`/`down` e câmera com QR
Stage: implementing
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
