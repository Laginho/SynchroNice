# SYN-004: PC envia texto e arquivos
Stage: done
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

- 2026-09-20 Attempt 2 failed: exit 0; blocked after two attempts. Log tail: I reproduced the exact same blocker two prior sessions hit: `cargo --version` is denied by both Bash and PowerShell tools, even though `.claude/settings.json` has `Bash(cargo:*)` and no local override exists. This is a fresh session (not a permission cache issue) — the allow rule just isn't matching for `cargo`, confirming this is the Claude Code-level bug flagged in commit `75af933`. /  / SYN-004 dispatches to stage 2 (`Stage: to-implement`, no ticket branch yet), but I can't proceed — stage 2 needs real `cargo test` red/green proof, and no subagent can substitute for that. /  / I need one of: / 1. You fix the `cargo` permission-matching issue at the Claude Code app/settings level, or / 2. You run `cargo` commands yourself at checkpoints I flag and relay output back to me. /  / Which do you want? I haven't touched the ticket or created a branch, so no commit is needed yet — but let me know if you'd like me to log this attempt on the ticket regardless. /

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

- 2026-09-20 (stage 2, notas para o revisor): **id do remetente e
  confirmação implementados**, resolvendo o achado da revisão de SYN-002 no
  topo destes Comments — o PC (remetente aqui) gera o id em
  `Outbox::enqueue_*`, o anúncio de `/listen` carrega esse id, e só
  `POST /outbox/{id}/ack` remove o Item da fila de Pendentes.

  Rotas novas (`pc/src-tauri/src/server.rs`), nenhuma delas ditada pelo
  ticket além de `/listen` (está no bloco de Verification): `GET /listen`
  bloqueia até haver ao menos um Pendente, devolve os de agora (um por
  linha) e fecha — long-poll clássico, não um stream mantido aberto; o
  celular reabre para continuar escutando. Isso simplificou bastante o lado
  servidor e o teste (resposta comum com Content-Length, sem parser de
  chunked encoding). `GET /outbox/{id}/file` baixa os bytes; `POST
  /outbox/{id}/ack` confirma. Presença (`server::Presence`) é um contador de
  conexões `/listen` vivas, não um booleano, para uma segunda conexão não
  apagar a presença que a primeira ainda segura.

  `pc_lib::outbox` é módulo novo, fila separada da `Items` (Sessão em
  memória de recebidos) — persistida em `manifest.json` + um arquivo por
  Item de arquivo, no diretório de config do app. `next_id` também persiste,
  nunca reaproveitado mesmo depois de confirmações removerem Itens.

  Os 4 testes do "Tests stage 2 writes" viraram 4 funções em
  `tests/server.rs`; os dois primeiros (texto Pendente sem cliente, fila
  sobrevive a recriar do mesmo diretório) testam `Outbox` direto — sem rota
  HTTP correspondente, enfileirar é ação local da janela, não algo que o
  celular manda pela rede. Precisei reescrever `sobe()` (Pasta passou a
  subdiretório de uma raiz temporária só, para caber a fila de saída do
  lado); as 11 funções antigas já verdes só tiveram a desestruturação e os
  caminhos ajustados, nenhuma asserção mudou — fmt disso foi commit `style`
  à parte, mesmo padrão do SYN-003.

  UI (`pc/ui/main.ts`, `pc/index.html`, `styles.css`): composer, drop
  (`getCurrentWebview().onDragDropEvent`), Ctrl+V de imagem (Clipboard API
  do WebView2, sem depender de Rust) e de arquivo (`read_clipboard_file_list`
  via `clipboard-win`, formato `CF_HDROP` que a Clipboard API não expõe) e o
  indicador de presença — tudo implementado, mas **critérios 3, 4, 5, 6 e 8
  são só verificação manual** (o próprio ticket já marca isso no bloco de
  Verification); não tenho como abrir o app Windows e testar drag/drop,
  colar ou o indicador piscando nesta sessão. Gate completo verde (fmt,
  clippy, 15/15 testes, tsc) é a garantia que dá para dar de dentro daqui.

  Sem thumbnail para imagem enviada (ao contrário de recebida): não é
  critério de aceite deste ticket, e os bytes do arquivo enviado vivem na
  fila interna, não na Pasta que `read_file_bytes` lê — ficaria para um
  ticket que pedisse isso.

- Débito humano: arrastar um arquivo do Explorer para a janela e ver a bolha
  de arquivo à direita, Pendente (critério 3 — o drop nativo do WebView2 não
  se reproduz sem o app aberto).
- Débito humano: Ctrl+V com um print no clipboard cria bolha de imagem à
  direita (critério 4 — confirmar que, lendo do `clipboardData` do evento, o
  WebView2 não pede permissão nenhuma).
- Débito humano: Ctrl+V com um e com dois arquivos copiados no Explorer cria
  uma e duas bolhas de arquivo à direita (critério 5).
- Débito humano: com um cliente em `/listen` (`curl -N`) o ponto de presença
  fica verde e volta a cinza ao cortar a conexão (critério 8); observar de
  quebra o piscar a cada envio do PC.
- Débito humano: sair pela bandeja, reabrir, e confirmar que o chat abre
  vazio com os Pendentes ainda na fila (critério 7 — a correção da revisão
  está coberta só por leitura de código).

- 2026-09-20 (revisão, achados deixados fora desta correção): três coisas
  vistas na revisão que não são critério deste ticket e não valem reabri-lo.
  (a) **A bolha de arquivo enviada desalinha**: `.item.file` é um grid de duas
  colunas (`pc/ui/styles.css`), e a bolha enviada tem `.file-info` e `.rodape`
  como filhos, então os dois caem lado a lado numa linha só; a regra
  `.item.file time { grid-column: 1/3 }` não alcança o `<time>` de dentro do
  `.rodape`. Cosmético, a bolha aparece à direita do mesmo jeito. (b)
  **`send_files` lê o arquivo inteiro para a RAM** (`std::fs::read` em
  `pc/src-tauri/src/lib.rs`) antes de `enqueue_file` gravá-lo de volta na
  fila: um vídeo grande passa inteiro pela memória, contra a história 31 da
  spec ("leve em disco e RAM"). (c) **Arrastar uma pasta aborta o resto do
  lote**: `fs::read` num diretório dá erro e `send_files` retorna no primeiro
  erro, então os caminhos depois dele no mesmo drop somem sem aviso (só
  `console.error`). (b) e (c) precisam de teste novo para fechar.

#### Resolution (2026-09-20)

Verdict: Needs your call: as duas correções desta revisão são comportamento
de janela que não consigo rodar — a do critério 4 em especial troca o caminho
de leitura do clipboard com base em fontes do wry/tauri, não em teste com o
app aberto.

**Achado 1 — critério 7, o chat não abria vazio (corrigido, `e7b67e4`).**
A janela chamava `list_pending` na abertura e desenhava cada Pendente
persistido como bolha; `main()` só roda no início do processo, que é
exatamente o caso que o critério nomeia — "o chat visível está vazio, a fila
não". Contradizia também o glossário ("ao encerrar, o texto da Sessão é
descartado") e a spec ("Sessão em memória… encerrar de fato o app limpa").
Sumiu o comando `list_pending` (`pc/src-tauri/src/lib.rs`) e a replay na
abertura (`pc/ui/main.ts`); junto, `renderEnviado` passou a ignorar a
confirmação de um id que esta Sessão nunca desenhou — sem isso, o celular
buscando um Pendente de antes do reinício faria brotar uma bolha já Entregue
num chat que tem que abrir vazio.

**Achado 2 — critério 4, Ctrl+V de print dependia de permissão (corrigido,
`1903a91`).** A janela lia o print por `navigator.clipboard.read()`. No
WebView2 essa API exige o grant de `CLIPBOARD_READ`, e o wry só instala o
handler que o concede quando o app chama `enable_clipboard_access()`
(`wry-0.55.1/src/webview2/mod.rs:497-515`); `WebviewAttributes::clipboard`
nasce `false` (`tauri-runtime-2.11.3/src/webview.rs:516`), nada em
`pc/src-tauri` liga isso e não existe chave para ele no `tauri.conf.json`.
Sem o grant a leitura cai no prompt do próprio WebView2 — e colar um print
não pode abrir caixa de diálogo. A imagem passou a sair do `clipboardData`
do evento de colagem, que não pede permissão, extraída de forma síncrona
(depois de um `await` o `clipboardData` já está vazio). Junto, a lista
`CF_HDROP` do Explorer passou a ser consultada **antes** da imagem: é o
sinal inequívoco de "copiei arquivos", e sem essa ordem a miniatura que o
Explorer põe no clipboard junto de um `.png` faria um arquivo copiado virar
bolha de imagem sem nome, quebrando o critério 5.

**Critérios.** 1, 2 e 7 (metade da fila) cobertos por teste; 3, 5, 6 e 8
conferidos por leitura do caminho ponta a ponta (evento do DOM → comando
Tauri → `Outbox` → evento `enviado` → bolha), nomes de comando, argumento,
evento e seletor todos batendo; 6 é inteiramente estático — `pc/index.html`
tem um único `<button>`, o `.send`, nenhum `type="file"` e nenhum ícone de
anexo, e `main.ts` nunca cria `button` nem `input`. 4 é o único que não
consigo fechar nem por leitura, por isso o `Needs your call`.

**Fila de Pendentes: a spec ganhou do ticket, e certo.** O "What to build"
deste ticket diz "arquivo por caminho"; a spec diz o contrário ("arquivo
copiado para uma pasta interna do app, nunca só o caminho, porque PNG
temporário, arquivo movido e URI de Compartilhar do Android não sobrevivem
até a entrega"). O stage 2 seguiu a spec — `enqueue_file` copia os bytes
para `<id>.bin` no diretório da fila. É a leitura correta.

**Prova red-before-green.** `0f879e2` toca só `tests/server.rs` e o ticket;
`pc/src-tauri/src/outbox.rs` não existia nesse commit (`git ls-tree 0f879e2
pc/src-tauri/src/`) e o arquivo de teste importa
`pc_lib::outbox::{OutBody, OutItem, Outbox}` — o crate de teste não
compilava, vermelho exatamente pela razão que "Tests stage 2 writes"
previu (fila, escuta e download não existem). `b291fb9` toca só `src/`,
sem tocar em teste; `52db6d2` só UI e `lib.rs`. A separação do `diff
--stat` está limpa.

**Gate verde** (`powershell -ExecutionPolicy Bypass -File .\gate.ps1`),
depois das duas correções: `cargo fmt --check`, `cargo clippy`, 19 testes
(4 em `config.rs`, 15 em `server.rs`), `tsc --noEmit`. `gate: passou`.

**Arquivos das correções.** `pc/ui/main.ts`, `pc/src-tauri/src/lib.rs` —
ambos dentro dos Primary files, nenhum teste novo (interface está fora de
teste automático pela spec).

**Deixado de fora de propósito**, cada um registrado em Comments acima ou no
ticket vizinho: o piscar da presença (vai para SYN-009, é a cadência de
reconexão do celular que o decide), o layout da bolha de arquivo enviada,
`send_files` lendo o arquivo inteiro para a RAM, e o drop de uma pasta
abortando o resto do lote. Nenhum é critério deste ticket, e os dois últimos
precisariam de teste novo para fechar — reabrir por eles seria maior que o
achado.
