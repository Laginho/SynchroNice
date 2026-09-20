# SYN-004: PC envia texto e arquivos
Stage: to-implement
Status: ready-for-agent
Blocked by: SYN-002
Review: agent

- Primary files:
  - pc/src-tauri/src/** (fila de saída persistida, rota de escuta para o cliente, rota de download, presença, leitura de clipboard)
  - pc/src-tauri/tests/server.rs
  - pc/src-tauri/Cargo.toml (clipboard de arquivos do Windows)
  - pc/ui/** (composer, drop, Ctrl+V, bolha à direita com estado Pendente/Entregue, indicador de presença)

#### What to build

O PC manda coisas ao celular. Enter envia o texto do composer. Arrastar um
arquivo do Explorer para a janela envia o arquivo. Ctrl+V com imagem no
clipboard envia um PNG; Ctrl+V com arquivos copiados no Explorer envia cada
um. Sem botão de anexar. Cada envio vira bolha à direita, Pendente (opacidade
reduzida, indicador girando) até um cliente pareado buscar o Item, quando
passa a Entregue com hora.

Direção PC para celular: o cliente mantém uma conexão de escuta (long-poll ou
similar) enquanto está aberto e recebe os Itens anunciados; arquivo ele baixa
por uma rota própria. A conexão de escuta ativa define presença: o chat mostra
se o outro lado está na rede. A fila de saída é gravada em disco e sobrevive a
sair e reabrir o app; texto em linha, arquivo por caminho.

#### Acceptance criteria

1. Enter com texto no composer cria bolha à direita, Pendente; sem cliente conectado ela fica Pendente indefinidamente.
2. Um cliente que abre a conexão de escuta recebe todos os Pendentes em ordem, cada um passa a Entregue no chat.
3. Arrastar um arquivo do Explorer para a janela cria bolha de arquivo à direita, Pendente.
4. Ctrl+V com um print no clipboard cria bolha de imagem à direita.
5. Ctrl+V com um arquivo copiado no Explorer cria bolha de arquivo à direita; dois arquivos copiados criam duas bolhas.
6. Não existe botão de anexar na interface.
7. Sair pela bandeja e reabrir: os Pendentes continuam na fila e são entregues quando um cliente conectar (o chat visível está vazio, a fila não).
8. O indicador de presença mostra "na rede" enquanto há conexão de escuta ativa e "fora" caso contrário.

#### Verification

    curl -N http://localhost:<porta>/listen -H "Authorization: Bearer <token>"
    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Critérios 3, 4, 5, 6 e 8: manual.

## Tests stage 2 writes (own commit, red)

- `pc/src-tauri/tests/server.rs`: enfileirar texto sem cliente deixa o Item Pendente; abrir a escuta entrega os Pendentes em ordem e eles ficam Entregues; enfileirar arquivo e baixar pela rota devolve bytes idênticos; fila gravada em disco sobrevive a recriar o servidor a partir do mesmo diretório. Vermelho porque fila, escuta e download não existem.

## Comments

- 2026-09-20 (revisão de SYN-002): **o id de Item ainda é do destinatário, e
  este ticket é quem precisa consertar isso.** A spec diz, em "Protocolo de
  Itens": "Cada Item leva um id gerado pelo remetente; o destinatário responde
  confirmando o id e ignora id já recebido. Só a confirmação remove o Item da
  fila de Pendentes, então reenvio nunca duplica bolha nem arquivo." Hoje
  `Items::push_text` (`pc/src-tauri/src/items.rs`) cunha `inner.items.len() + 1`
  e `POST /items/text` responde 201 sem corpo, de propósito — o cabeçalho de
  `pc/src-tauri/src/server.rs` explica que nenhum cliente deve nascer
  dependendo de um id do PC. Consequência para este ticket: o `Item.id` de
  hoje é chave de render local, não id de protocolo; a confirmação e a
  deduplicação por id não existem em lugar nenhum e são pré-requisito da fila
  de Pendentes do critério 1. Ao implementar, o id do remetente entra na
  requisição (e no anúncio da rota de escuta) e o campo local ganha outro
  nome, para os dois não se confundirem.

- 2026-09-20 Attempt 1 stopped to ask: Same hard blocker as SYN-003: `cargo` is denied in this session (Bash and PowerShell, with sandbox override too — not a one-off prompt, every invocation is auto-denied). Nothing about SYN-004 itself is blocked — this is environment-wide, so it'll hit every future ticket the same way. /  / This isn't a "Humano simulado" case (no fable-high subagent can substitute a real compiler run for red/green proof), so I'm stopping before touching the ticket or creating a branch, same as the SYN-003 session did. /  / I need one of: / 1. You approve `cargo` execution in this session (Bash or PowerShell), so I can run tests myself, or / 2. You run `cargo test` / `.\gate.ps1` on your end at checkpoints I flag and relay output back. /  / Which do you want — and does #1 look fixable on your side (e.g. a permission setting), or is #2 the way forward for this session? /

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

- 2026-09-20 Attempt 1 failed: exit 0. Log tail: SYN-004 dispatches to stage 2 (`Stage: to-implement`, no ticket branch exists yet), but I hit the same wall the prior two sessions logged: `cargo --version` is denied by both the Bash and PowerShell tools, even though `.claude/settings.json` correctly has `Bash(cargo:*)` (added in `66ed735`) and `git --version` works fine through the same Bash tool. The human's fix on the ticket claims this was resolved and verified green at 09:59 today, but it's reproducing in this fresh session. /  / Since stage 2 needs real `cargo test` red/green proof (no subagent can fake that), I'm stopping before touching the ticket rather than looping a third identical "Attempt N failed" commit. Since you're right here: can you run `cargo --version` yourself in this same session/terminal and tell me whether it prompts, denies, or works? That'll tell us if it's a per-session permission cache issue (fixed by restarting the session) versus the Code-level bug attempt 2 already flagged. /
