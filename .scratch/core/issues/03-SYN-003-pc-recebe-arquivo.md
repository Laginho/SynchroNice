# SYN-003: PC recebe arquivo pela rede
Stage: to-implement
Status: ready-for-agent
Blocked by: SYN-002
Review: agent

- Primary files:
  - pc/src-tauri/src/** (rota de arquivo em stream, gravação na Pasta, progresso)
  - pc/src-tauri/tests/server.rs
  - pc/ui/** (bolha de arquivo: miniatura, nome/tipo/tamanho, barra de progresso animada)

#### What to build

Uma requisição com token, nome e tamanho do arquivo, e os bytes em stream,
grava o arquivo na Pasta e cria um Item de arquivo no chat. Imagem aparece
como miniatura; outro tipo mostra nome, extensão e tamanho. Enquanto os bytes
chegam, a bolha mostra uma barra de progresso animada. Nome repetido na Pasta
vira `nome (1).ext`, `nome (2).ext`. O arquivo é gravado com nome temporário
e renomeado só ao terminar, para nunca deixar arquivo pela metade com nome
final.

#### Acceptance criteria

1. Enviar uma imagem via `curl` grava o arquivo na Pasta e mostra miniatura na bolha à esquerda.
2. Enviar um PDF grava o arquivo e mostra nome, `PDF` e tamanho legível na bolha.
3. Enviar `foto.jpg` duas vezes resulta em `foto.jpg` e `foto (1).jpg` na Pasta, ambos íntegros.
4. Durante um envio de mais de 100 MB, a bolha mostra progresso crescendo e uma animação visível.
5. Um envio interrompido no meio não deixa arquivo com nome final na Pasta.
6. Um nome de arquivo com caminho (`../x.txt`) ou caracteres inválidos no Windows é saneado e o arquivo fica dentro da Pasta.

#### Verification

    curl -X POST http://localhost:<porta>/items/file -H "Authorization: Bearer <token>" -H "X-File-Name: foto.jpg" --data-binary @foto.jpg
    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Critérios 1, 2 e 4: manual.

## Tests stage 2 writes (own commit, red)

- `pc/src-tauri/tests/server.rs`: POST de bytes com nome grava na Pasta temporária com conteúdo idêntico; segundo POST de mesmo nome gera sufixo ` (1)`; nome com `../` fica dentro da Pasta; stream fechado no meio não deixa arquivo com nome final. Vermelho porque a rota não existe.

## Comments

- 2026-09-20 Attempt 1 stopped to ask: I've hit a hard blocker: every attempt to run `cargo` (test, `--version`, even `where cargo`) in this session returns "This command requires approval" and gets denied — across both Bash and PowerShell, with or without sandbox override. `ls`/`git` work fine, so it's specifically compiler/toolchain execution that's gated, and nothing here is granting it. /  / This matters because stage 2 of `ticket-flow` requires red-before-green proof and a green gate before `Stage: to-review` — I can't fabricate that. Current state, nothing committed yet: /  / - On branch `syn-003` (off `sweatshop/2026-09-19-2324`). / - Ticket's `Stage:` edited to `implementing` (uncommitted). / - `pc/src-tauri/tests/server.rs` rewritten with the file-upload tests the ticket specifies, plus the matching helper/signature changes (uncommitted) — but unverified as actually red, since I can't compile. /  / I need one of these to continue: / 1. You approve a Bash/PowerShell `cargo` call when prompted, so I can confirm red then implement and confirm green myself, or / 2. You run `cargo test` (and the full `gate.ps1`) on your end at the checkpoints I flag, and relay the output back to me. /  / Which do you want? /

- 2026-09-20 (resposta do humano): **opção 1 — permissão concedida, `cargo`
  liberado.** Era permissão, não ambiente: o `sweatshop.ps1` monta
  `--allowedTools` com `git`, `gh`, `powershell` (primeiro token do gate) e
  `npx`; `cargo` nunca esteve na lista — por isso o stage 3 passava (só chama
  o gate via `powershell`) e o stage 2 morria. Corrigido em
  `.claude/settings.json` (`Bash(cargo:*)`, `Bash(gradlew.bat:*)`,
  `Bash(.\gradlew.bat:*)`), commit `66ed735` nesta branch de sessão. Rode
  `cargo` direto, sem pedir aprovação. Toolchain confere: VS Build Tools
  (MSVC/linker) e rustup instalados; `gate.ps1` passou verde nesta máquina
  hoje às 09:59 (8 testes — 4 em `config.rs`, 4 em `server.rs` — mais `tsc
  --noEmit`). JDK 17 (Microsoft) e `JAVA_HOME` prontos para quando `android/`
  existir (SYN-007). Nada ficou pendente das tentativas anteriores: o driver
  reverteu tudo, não há branch `syn-003`/`syn-004`/`syn-005` órfã. Stage 2
  começa do zero, fluxo normal.

- 2026-09-20 Attempt 1 failed: exit 0. Log tail: ...ix (`Bash(cargo:*)`) that the ticket says was verified working this morning. /  / I tried: / - `cargo --version` via Bash (with and without sandbox override) → `"This command requires approval"` / - `cargo --version` via PowerShell → same, flagged as needing approval / - `node --version` (no allow rule at all) → runs fine /  / So it's not a blanket restriction — this session specifically denies `cargo`/`gradlew` even though the allow rule is present and no deny rule or hook exists anywhere I can find (`.claude/settings.json`, `~/.claude/settings.json`, no `settings.local.json`, no hooks). This is environment-level, not something a ticket decision can route around — there's no substitute for an actual compiler run for red/green proof. /  / Current state, nothing committed: on branch `syn-003` (off `sweatshop/2026-09-19-2324`), ticket's `Stage:` edited to `implementing` and `pc/src-tauri/tests/server.rs` rewritten with the four file-upload tests the ticket specifies — both uncommitted. /  / Can you check why cargo is still being denied in this interactive session specifically? Or, if you'd rather, run `cargo test --manifest-path pc/src-tauri/Cargo.toml` yourself right now to confirm one way or the other. /
