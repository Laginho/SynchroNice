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
