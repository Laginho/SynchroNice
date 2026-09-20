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

1. ❌ Enviar uma imagem via `curl` grava o arquivo na Pasta e mostra miniatura na bolha à esquerda. — grava, mas a miniatura nunca aparece com o `curl` do bloco Verification (review 2026-09-20, achado 1).
2. ❌ Enviar um PDF grava o arquivo e mostra nome, `PDF` e tamanho legível na bolha. — grava, mas a bolha fica presa no estado "enviando", sem o tipo nem o tamanho (mesmo achado 1).
3. ⚠️ Enviar `foto.jpg` duas vezes resulta em `foto.jpg` e `foto (1).jpg` na Pasta, ambos íntegros. — vale em sequência (teste verde); dois envios simultâneos do mesmo nome perdem um dos arquivos (achado 2).
4. ❌ Durante um envio de mais de 100 MB, a bolha mostra progresso crescendo e uma animação visível. — a barra fica em 0% sem `X-File-Size` (mesmo achado 1).
5. ✅ Um envio interrompido no meio não deixa arquivo com nome final na Pasta.
6. ✅ Um nome de arquivo com caminho (`../x.txt`) ou caracteres inválidos no Windows é saneado e o arquivo fica dentro da Pasta.

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

- 2026-09-20 Attempt 2 stopped to ask: same wall, one more cause ruled out.
  `cargo --version` via the Bash tool → "This command requires approval";
  same call with the sandbox override (`dangerouslyDisableSandbox: true`) →
  identical error, so it isn't the sandbox layer gating it. Re-checked
  everything from Attempt 1 fresh: `.claude/settings.json` still has
  `Bash(cargo:*)`, no `.claude/settings.local.json` exists, and
  `~/.claude/settings.json` has no `permissions` block at all (nothing there
  to conflict). The allow rule is present and correct, and the command is
  still denied before any approval prompt reaches anyone. This points at a
  permission-matching bug in the harness for `cargo`/`gradlew` specifically
  (both flagged the same way in every attempt so far), not a settings
  mistake anyone can fix from inside this repo. Nothing touched — no branch,
  no file changes — same as the prior two "stopped to ask" sessions. (SYN-003)

- 2026-09-20 Attempt 2 stopped to ask: Same wall as before, one cause ruled out: `cargo --version` is denied via the Bash tool even with `dangerouslyDisableSandbox: true`, so it's not the sandbox layer. `.claude/settings.json` still has `Bash(cargo:*)`, no local override exists anywhere, and `~/.claude/settings.json` has no permissions block to conflict with it. The allow rule is correct and present, yet the command is still denied before any prompt reaches you. /  / This looks like a permission-matching bug in Claude Code itself for `cargo`/`gradlew` specifically, not something fixable from inside this repo. Logged as Attempt 2 on the ticket (commit `a8d037b`), nothing else touched — no branch, no code changes. /  / I need one of: / 1. You investigate/fix the `cargo` denial at the Claude Code app/settings level (outside this repo), or / 2. You run `cargo test --manifest-path pc/src-tauri/Cargo.toml` and `.\gate.ps1` yourself and relay output back to me at checkpoints. /  / Which do you want? /

- 2026-09-20 (resposta do humano, corrigindo a anterior): **`cargo` não foi
  liberado — a resposta acima estava errada.** O driver `sweatshop.ps1` passa
  `--allowedTools`, que *restringe* o conjunto: só entram `git`, `gh`, `npx` e
  `powershell` (primeiro token do gate). Regra `allow` em
  `.claude/settings.json` não amplia isso — medido, o `66ed735` está inerte.
  Não chamem `cargo` direto: é negado e não há como liberar de dentro do repo.
  Solução, commit `0845832` nesta branch: `gate.ps1` ganhou `-TestsOnly` (só
  testes, sem fmt/clippy/tsc). **Runner de testes do stage 2:**
  `powershell -ExecutionPolicy Bypass -File .\gate.ps1 -TestsOnly` — já
  liberado porque entra por `powershell`. Verificado: passou, 8 testes (4 em
  `config.rs`, 4 em `server.rs`). O gate completo (sem o switch) continua
  obrigatório antes de `Stage: to-review`; `-TestsOnly` é só para a prova
  red-before-green. `AGENTS.md` ("Bindings do fluxo") já diz isso. Nada
  pendente das tentativas anteriores: driver reverteu tudo, sem branch órfã.

- 2026-09-20 Stage 2, decisões de implementação (não são blockers, registro
  para a review): **Item ganhou um `body` (enum `ItemBody::Text|File`)** em
  vez de manter `text` solto — o Item de arquivo precisa de `name`, `size` e
  `received` (bytes já gravados) para a barra de progresso; texto e arquivo
  não cabiam mais no mesmo shape plano. `Items::start_file` cria o Item com
  `received: 0` assim que o upload começa (a bolha nasce antes dos bytes
  chegarem) e `file_progress` atualiza e reemite pelo mesmo canal (`on_new`)
  — o front end distingue criação de atualização pelo id já visto. /  /
  **Pasta não escolhida ainda:** `server::spawn` recebe `Option<PathBuf>`;
  sem Pasta, `/items/file` devolve 503 em vez de travar o servidor (texto
  continua funcionando). É capturado uma vez no boot (`start_server`); se o
  usuário escolher a Pasta depois do primeiro diálogo, o servidor só vê isso
  num restart — não implementei atualização ao vivo, fora do que os critérios
  de aceite pedem. /  / **Miniatura de imagem:** não usei o asset-protocol do
  Tauri (escopo dinâmico por Pasta arbitrária, API que eu não conseguiria
  verificar sem rodar o build). Em vez disso, um comando novo,
  `read_file_bytes`, devolve os bytes do arquivo já gravado (mesma
  sanitização do nome) e o front monta um `Blob`/`ObjectURL` — só chamado
  depois que o arquivo termina de chegar. Critérios 1, 2 e 4 continuam
  manuais (curl não testa UI); os 4 testes automatizados (conteúdo íntegro,
  sufixo `(1)`, `../` contido na Pasta, envio cortado sem nome final) estão
  verdes no gate completo.

- Débito humano: critérios 1, 2 e 4 (miniatura, nome/tipo/tamanho, barra
  animada) não foram vistos numa tela — a review os avaliou traçando o caminho
  do dado do servidor até o DOM, não abrindo o app. Foi o bastante para
  reprová-los (achado 1), mas a aprovação deles, quando o stage 2 corrigir,
  ainda quer um olho humano.

#### Review (2026-09-20) — reaberto

Verdict: Needs your call: os critérios 1, 2 e 4 não passam com o próprio `curl`
do bloco Verification, e a correção mexe no protocolo (o servidor passa a ler
o tamanho do `Content-Length`) — vale uma olhada humana no contrato antes de o
stage 2 refazer.

Higiene do fluxo, em ordem: separação teste/código limpa — `3f10528` toca só
`tests/server.rs` e o ticket, `c713b12` não toca nenhum arquivo de teste.
Vermelho confirmado: em `3f10528` os testes usam `pc_lib::items::ItemBody` e
chamam `server::spawn` com 4 argumentos, e nenhum dos dois existia no código
daquele commit — os 4 testes novos não compilavam. Gate completo verde agora
nesta máquina: fmt, clippy, 12 testes (4 em `config.rs`, 8 em `server.rs`),
`tsc --noEmit`.

**Achado 1 (bloqueia 1, 2 e 4).** `post_file` tira o tamanho só do cabeçalho
`X-File-Size`, com `.unwrap_or(0)`, e ignora o `Content-Length`. O `curl` do
bloco Verification deste ticket não manda `X-File-Size` — manda
`Content-Length`. Com `size: 0`, `atualizarProgresso` em `pc/ui/main.ts` calcula
`completo = item.size > 0 && item.received >= item.size`, que nunca fica
verdadeiro: a miniatura (que só carrega dentro do `if (completo)`) não aparece,
o texto `PDF · 1,2 MB` nunca substitui a contagem de bytes, e
`barra.style.width` (também sob `item.size > 0`) fica em `0%`, então a animação
listrada roda numa barra de largura zero. Um arquivo de 0 byte cai no mesmo
buraco, mesmo com `X-File-Size` correto. O fim do recebimento precisa de um
sinal próprio, não pode ser inferido de `received >= size`.

**Achado 2 (enfraquece 3).** `dedup_path` decide o nome final por `exists()` e
nada reserva esse nome até o `rename` do fim. Dois envios de `foto.jpg` que se
sobrepõem no tempo resolvem os dois para `foto.jpg` (só o `.part-<hex>` é
único) e o segundo `rename` sobrescreve o primeiro em silêncio. O teste
`segundo_post_do_mesmo_nome_ganha_sufixo` é sequencial, por isso passa.

O que falta, para o stage 2 continuar nesta mesma branch:

1. O servidor usa `Content-Length` quando `X-File-Size` não vier, e a UI marca
   o arquivo como completo por um sinal explícito do fim do stream — não por
   `received >= size`. Critérios 1, 2 e 4 voltam a valer com o `curl` do bloco
   Verification, sem cabeçalho extra, e um arquivo de 0 byte também termina.
2. Reservar o nome final antes de começar a gravar (criar o destino vazio e
   gravar por cima, ou algum aluguel equivalente), para dois envios simultâneos
   do mesmo nome darem `foto.jpg` e `foto (1).jpg` — critério 3 com "ambos
   íntegros" valendo também fora da sequência.

Testes novos que isso pede (por isso é reabertura e não conserto na review):
POST sem `X-File-Size` cria o Item com o tamanho do `Content-Length`; dois POSTs
concorrentes do mesmo nome deixam os dois arquivos íntegros na Pasta.

Achados menores, fora do que bloqueia — não mexer neles aqui:

- Todo envio interrompido deixa um `<nome>.part-<hex>` na Pasta para sempre; o
  caminho de erro de `post_file` não apaga o temporário. Critério 5 continua
  valendo (o nome final não aparece), mas o lixo acumula num diretório do
  usuário. Vira `CLEAN-*` ou critério novo no stage 1.
- `read_file_bytes` lê o arquivo inteiro na memória e o manda pelo IPC do Tauri
  como array JSON de números (~4x os bytes) a cada miniatura. Decisão já
  registrada pelo stage 2; cara para os 100 MB que o critério 4 cita.
- `sanitize_file_name`: `trim_matches('.')` renomeia dotfile legítimo
  (`.gitignore` → `gitignore`), e nome reservado do Windows (`COM1`, `nul.txt`)
  chega no `File::create` e vira 500. Critério 6 fala de caracteres, então
  passa; ainda assim é superfície para o stage 1 decidir.
- `random_suffix` em `server.rs` repete o final de `config.rs::new_token`
  (`getrandom::fill` + hex). Um `hex(&[u8])` compartilhado resolve. `CLEAN-*`.
- `sanitize_file_name` virou `pub(crate)` só para `lib.rs::read_file_bytes`
  refazer `pasta.join(sanitize(...))`: a invariante "não sai da Pasta" agora
  mora em dois lugares. Um `caminho_na_pasta(pasta, name)` a guardaria uma vez.
- `URL.createObjectURL` em `carregarMiniatura` nunca é revogado.

- 2026-09-20 (resposta do humano à review): **direção aprovada, com dois
  ajustes.** O `curl` do bloco Verification é o cliente de referência — a spec
  manda ("PC primeiro, testável com `curl` antes de existir Kotlin") e é contra
  o bloco de protocolo no topo de `pc/src-tauri/src/server.rs` que SYN-008
  (bloqueado por este ticket) vai escrever o cliente Kotlin. Ele não ganha
  cabeçalho novo. A spec pede "nome, tamanho e bytes em stream" sem dizer onde
  o tamanho viaja; num POST de corpo cru o `Content-Length` *é* o tamanho.
  Ajuste 1: **`Content-Length` primeiro, `X-File-Size` só quando o corpo vier
  chunked** (sem `Content-Length`) — não o inverso. Se os dois vierem e
  divergirem, o corpo entrega exatamente `Content-Length` bytes; um `size`
  diferente vira barra que passa de 100% ou nunca chega. Sem nenhum dos dois
  (Android com provider que não informa tamanho), aceita com `size: 0`: a bolha
  conta bytes e termina pelo sinal de fim, não é 400. Atualizar o bloco de
  protocolo do `server.rs` de acordo. **Fim explícito**, como a review pediu:
  `Items::file_done(id)` pelo mesmo `on_new`, emitido *depois* do `rename` —
  hoje `file_progress` com `received == size` dispara antes do rename
  (`server.rs:177` vs `:181`) e a UI chamaria `read_file_bytes` num nome final
  que ainda não existe; o sinal separado resolve isso e o arquivo de 0 byte de
  uma vez. `atualizarProgresso` (`pc/ui/main.ts:107`) passa a olhar esse campo.
  Envio cortado não emite o sinal e a bolha fica em "enviando": aceito, vai
  junto com o `.part` órfão que a review já mandou para `CLEAN-*`. Ajuste 2:
  **reserva do nome por aluguel em memória, não arquivo vazio.** Só este
  processo grava na Pasta, então um conjunto de nomes alugados no `AppState`,
  consultado em `dedup_path` sob o mesmo lock que o `exists()`, basta; o "criar
  o destino vazio" deixaria um `foto.jpg` de 0 byte visível na Pasta durante o
  envio e pediria limpeza em cada caminho de erro. Liberar o aluguel em *todo*
  retorno de `post_file` — senão um envio cortado de `foto.jpg` faz o próximo
  virar `foto (1).jpg` sem `foto.jpg` no disco. Por que não deixar como está:
  história 24 da spec diz "nunca sobrescrever", e SYN-008 critério 3 manda 3
  fotos de uma vez — colisão concorrente de nome é caminho normal, não canto
  obscuro. Testes (vermelhos antes, `gate.ps1 -TestsOnly`): POST sem
  `X-File-Size` cria o Item com `size == Content-Length` e termina; POST de 0
  byte termina com arquivo final de 0 byte na Pasta; dois POSTs concorrentes de
  `foto.jpg` (corpo lento, os dois abertos ao mesmo tempo) deixam `foto.jpg` e
  `foto (1).jpg` íntegros. Achados menores ficam fora deste ticket, como a
  review pôs. `Stage: to-implement` já está certo; stage 2 segue nesta branch,
  em cima de `6203341`.
