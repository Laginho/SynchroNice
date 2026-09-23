# SYN-002: PC recebe texto pela rede
Stage: done
Status: ready-for-agent
Blocked by: SYN-001
Review: agent

- Primary files:
  - pc/src-tauri/src/** (servidor HTTP, lista de Itens em memória, token)
  - pc/src-tauri/tests/** (New: server.rs)
  - pc/src-tauri/Cargo.toml (dependência do servidor HTTP)
  - pc/ui/** (renderizar bolha de texto à esquerda)

#### What to build

O PC vira servidor. Sobe um serviço HTTP na rede local numa porta fixa, com um
token secreto gerado e gravado na configuração (o QR que o expõe vem em
SYN-005). Uma requisição com o token e um corpo de texto cria um Item de texto
que aparece como bolha à esquerda no chat, com hora. Sem token ou com token
errado, recusa. Links na bolha abrem no navegador ao clicar.

#### Acceptance criteria

1. Com o app aberto, `curl` com o token e um texto faz a bolha aparecer no chat em menos de 1 s.
2. A mesma requisição sem token, ou com token errado, recebe recusa e nada aparece no chat.
3. Um texto contendo HTML aparece como texto literal, nunca renderizado.
4. Um texto que é URL vira link clicável que abre no navegador padrão.
5. Dois textos enviados em sequência aparecem em ordem de chegada.
6. Sair pela bandeja e reabrir mostra o chat vazio (Sessão descartada); o token permanece o mesmo.

#### Verification

    curl -X POST http://localhost:<porta>/items/text -H "Authorization: Bearer <token>" -d "olá"
    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Critérios 1, 4 e 6: manual.

## Tests stage 2 writes (own commit, red)

- `pc/src-tauri/tests/server.rs`: sobe o servidor em porta aleatória com token conhecido; POST de texto com token devolve sucesso e a lista de Itens contém o texto; POST sem token devolve 401 e a lista fica vazia; dois POSTs preservam ordem. Vermelho porque o servidor não existe.

## Comments

- 2026-09-17 (revisão de SYN-001): o passo `tsc --noEmit` do gate hoje não
  checa nada. `pc/tsconfig.json` tem `include: ["ui"]` e `pc/ui/` só contém
  `styles.css` e `vite-env.d.ts` — zero TypeScript, e `pc/index.html` não tem
  `<script>`. O critério 7 de SYN-001 está cumprido na letra, mas o passo só
  passa a valer quando este ticket adicionar o primeiro `.ts` e o `<script>`
  que o carrega. Vale conferir que o gate fica vermelho com um erro de tipo de
  propósito antes de confiar nele.

- 2026-09-19 (stage 2, branch `syn-002`):
  - **Red-green.** Testes em `aa15254` (só testes + `Stage: implementing`):
    `tests/server.rs` não compila porque `pc_lib::items` e `pc_lib::server`
    não existem (E0432); `tests/config.rs` não acha `load_or_create_token`
    (E0425). `d51dce7` é só o rustfmt do arquivo de teste. Código em
    `90a7b58`, sem tocar em `tests/`. Verde: 8 testes (4 em `config.rs`,
    4 em `server.rs`), gate `passou`.
  - **Protocolo.** `POST /items/text`, corpo UTF-8, `Authorization: Bearer
    <token>`; ~~201 com `{"id": n}`~~ **201 sem corpo** (corrigido em
    `6918d3a`, no mesmo branch, depois que esta linha foi escrita — ver a
    revisão de 2026-09-20); 401 sem token ou token errado. Porta fixa
    `47820` (`server::PORT`), bind em `0.0.0.0`. O Windows pode pedir
    liberação no firewall na primeira subida.
  - **Token.** Gerado uma vez (16 bytes aleatórios em hex) e gravado em
    `config.json` ao lado da Pasta — no Windows,
    `%APPDATA%\dev.synchronice.pc\config.json`. Reler o arquivo é como obter o
    `<token>` para o `curl` até o QR existir (SYN-005).
  - **Critérios.** 2 e 5: teste automático. 3: por construção
    (`textContent`, nunca `innerHTML`). 1, 4 e 6: manuais, ninguém observou
    ainda — precisam de `npx tauri dev` em `pc/`, `curl` do Verification com
    a porta 47820 e o token do config, e sair pela bandeja e reabrir.
  - **Fora dos Primary files:** `pc/index.html` ganhou a linha `<script
    type="module" src="/ui/main.ts">`. É o entry do Vite para a UI que os
    Primary files chamam de `pc/ui/**` (o próprio comentário acima previa "o
    `<script>` que o carrega"). Nada mais fora da lista.
  - **Gate do tsc agora morde.** Com `const erroDeTipo: number = "texto"`
    acrescentado em `ui/main.ts`, o gate imprime
    `error TS2322 ... FALHOU: tsc --noEmit (pc/ui)` e sai vermelho. Revertido.
    Responde ao comentário de 2026-09-17.
  - **Sem mudança de capability:** abrir link usa `openUrl` do
    `tauri-plugin-opener`, cujo `opener:default` já libera `http`/`https`.
  - `npx vite build` empacota `main.ts` (3 chunks, 207 ms).

- 2026-09-19 (foreman, limpeza de crash): o stage 3 anterior (fable max) morreu
  na cota do Fable no meio do review, depois de já ter aberto e mergeado o PR #2
  (`syn-002` -> `main`). Duas consequências para quem revisar agora:
  1. **O código já está na `main`** (merge `5d247eb`). Não há o que mergear; o
     que falta é o veredito. Os commits a revisar são `aa15254..6918d3a`.
  2. **Nenhum critério foi julgado.** O `6918d3a` (201 sem corpo) é a única
     marca que aquele review deixou. Ele nunca escreveu ❌ nem ✅ em lugar
     nenhum, e o `reopened` que o driver registrou no run-log é leitura de
     branch obsoleta, não um veredito.
  Critérios 1, 4 e 6 são manuais e continuam sem observação.

- 2026-09-19 Review ended at to-review (exit 0); branch syn-002 holds the review; left for a human

- 2026-09-20 (humano simulado): fiz o papel da pessoa que os critérios 1, 4 e
  6 esperavam. Julguei código e testes de `aa15254..6918d3a` (já na `main`)
  critério por critério; o veredito e o Resolution ficam para o stage 3.
  - **Ambiente.** Este checkout é um clone de hoje (`da8ef16 clone:`) em
    outra máquina (`DESKTOP-J8HB7V5`, perfil `Lage`; SYN-001 foi construído
    em `C:\Users\bruno`). **Não há toolchain Rust aqui**: nenhum `cargo.exe`
    ou `rustc.exe` em C: ou D:, sem `.cargo`/`.rustup`, sem WSL. Do gate, só
    `tsc --noEmit` roda (verde, exit 0). `npx vite build` também verde
    (9 módulos, `index-*.js` 2,75 kB, 245 ms) — o bundle resolve
    `@tauri-apps/plugin-opener` 2.5.5 e `@tauri-apps/api`. Por isso **não
    acrescentei teste**: um teste Rust que não posso compilar não prova nada
    e arrisca deixar o gate vermelho para o stage 3. O teste que faria, se
    houvesse cargo: em `tests/server.rs`, registrar `Items::on_new` com um
    canal, dar o POST e `try_recv()` o Item logo após o 201 — prova que a
    janela é avisada antes da resposta, a metade automatizável do critério 1.
  - **Critério 1 (curl → bolha em < 1 s).** Cadeia lida ponta a ponta:
    `server::post_text` → `Items::push_text` (`items.rs:36-51`), que chama
    os listeners de forma síncrona, ainda dentro do handler, antes do 201 →
    listener em `lib.rs:60-64` faz `handle.emit("item", item)` →
    `ui/main.ts:68` faz `listen("item")` **antes** de `invoke("list_items")`
    (`main.ts:69`), com `seen` desduplicando (`main.ts:33`) → `render` cria a
    bolha `.item` (à esquerda por `align-self: flex-start`,
    `styles.css:91`) com `<time>` da hora (`main.ts:57-58`). Não há polling
    nem fila: a latência é a do `emit`. Metade HTTP → lista tem teste
    (`post_com_token_cria_item_de_texto`). Sem observação humana.
  - **Critério 2 (sem token / token errado → recusa, nada no chat).** ✅.
    `post_sem_token_e_recusado_e_nada_entra` e
    `post_com_token_errado_e_recusado_e_nada_entra` sobem o servidor real
    (`server::spawn` em porta 0), batem via `ureq` e conferem 401 e lista
    vazia — produção, não cópia. O `emit` só acontece dentro de `push_text`,
    que a recusa não alcança (`server.rs:91-94`).
  - **Critério 3 (HTML vira texto literal).** ✅ por construção.
    `grep -rnE 'innerHTML|insertAdjacentHTML|outerHTML|srcdoc' pc/ui
    pc/index.html` → só o comentário em `main.ts:40`. Texto entra por
    `textContent` (`main.ts:47`, `main.ts:54`); no ramo de link, `href` só
    recebe URL que `new URL()` aceitou com protocolo `http:`/`https:`
    (`main.ts:17-26`), então nem `javascript:` passa.
  - **Critério 4 (URL vira link que abre no navegador padrão).** Handler:
    `asUrl` exige o texto inteiro ser uma URL http(s) sem espaço; o `<a>`
    recebe `click` → `preventDefault()` + `openUrl(url)` (`main.ts:44-52`),
    então a WebView não navega. Permissão: `capabilities/default.json` tem
    `opener:default`; o `permissions/default.toml` do `tauri-plugin-opener`
    2.5.5 (versão do `Cargo.lock`, lido no upstream porque o crate não está
    em disco aqui) = `allow-open-url` + `allow-reveal-item-in-dir` +
    `allow-default-urls`, descrito como "opening `mailto:`, `tel:`,
    `https://` and `http://` urls using their default application". Plugin
    registrado em `lib.rs:99`. Sem observação humana do clique.
  - **Critério 5 (ordem de chegada).** ✅. `dois_posts_preservam_ordem_de_chegada`
    via HTTP real. Na UI, os listeners rodam dentro do mesmo `Mutex`
    (`items.rs:37-49`), então os `emit` saem na ordem dos `push`, e
    `list_items` devolve `snapshot()` em ordem.
  - **Critério 6 (sair pela bandeja → chat vazio; token igual).** Token: ✅
    por teste real em disco, `token_e_gerado_uma_vez_e_persiste`
    (`tests/config.rs:20`); `load_or_create_token` só gera quando não há
    (`config.rs:42-51`). Sessão: por construção — `Items` é `Arc<Mutex<Vec>>`
    sem persistência (`items.rs`), o único acesso a disco em `src/` é
    `config.rs:17,24,26`, e "Sair" é `app.exit(0)` (`lib.rs:88`). Reabrir
    chama `list_items` → vazio → `.empty` continua visível. Fechar no X só
    esconde (`lib.rs:120-123`), coerente com o glossário. Sem observação
    humana.
  - **Separação teste/código.** `aa15254` só toca `tests/` + ticket;
    `90a7b58` não toca `tests/`; `6918d3a` (201 sem corpo) muda `server.rs`
    e os testes já não liam corpo, então continuam válidos.
  - **Decisão.** Nenhum defeito concreto que reprove critério. Volta para
    `to-review` para o stage 3 escrever o veredito. Aviso ao stage 3: **o
    gate não roda nesta máquina** — se precisar commitar um fix, não terá
    como provar verde aqui; `tsc` é o único passo disponível.
  - Débito humano: critério 1 — ninguém viu a bolha aparecer em < 1 s com `npx tauri dev` + `curl`; sem Rust nesta máquina, e rodar o app registraria `target/debug` no autostart do Windows e abriria o diálogo da Pasta sem aviso.
  - Débito humano: critério 4 — ninguém clicou num link recebido e viu o navegador padrão abrir; permissão e handler conferidos por inspeção, o salto `openUrl` → shell só é observável com o app rodando.
  - Débito humano: critério 6 — ninguém saiu pela bandeja e reabriu; "token permanece" tem teste, "chat vazio" é por construção (Sessão só em memória).
  - ~~Débito humano: gate completo (`cargo fmt --check`, `cargo clippy`,
    `cargo test`) não rodou nesta máquina (sem toolchain Rust); só
    `tsc --noEmit` e `vite build`, ambos verdes.~~ **Errado, e resolvido no
    stage 3 de 2026-09-20:** existe toolchain Rust nesta máquina
    (`C:\Users\Lage\.cargo\bin\cargo.exe`). O gate completo rodou aqui e
    passou — saída no Resolution abaixo. Não é mais débito.

#### Resolution (2026-09-20)

Verdict: Approve

Revisão de `aa15254..6918d3a` (já na `main` pelo merge `5d247eb`), nos dois
eixos do `code-review`: Standards (contra `CONTEXT.md`, `docs/adr/0001`,
`docs/adr/0002`, `AGENTS.md`, `docs/DECISIONS.md` e a linha de base de smells
de Fowler) e Spec (contra este ticket e `.scratch/core/spec.md`). Nenhum
critério é contrariado pelo código.

**Gate.** Rodou completo nesta máquina, `exit 0`:

    ==> cargo fmt --check (pc)
    ==> cargo clippy (pc)      Finished dev profile in 19.42s
    ==> cargo test (pc)        tests\config.rs: 4 passed; 0 failed
                               tests\server.rs: 4 passed; 0 failed
                               (lib/main/doc-tests: 0 testes)
    ==> tsc --noEmit (pc/ui)
    gate: passou

8 testes, todos verdes. Isto corrige o débito registrado em 2026-09-20 de que
não haveria Rust aqui: há.

**Separação teste/código (a regra que sustenta o loop).** `aa15254` toca só
`tests/` e o ticket; `d51dce7` é rustfmt do arquivo de teste; `90a7b58` não
toca `tests/`; `6918d3a` muda `server.rs` e não invalida nenhum teste (nenhum
lia o corpo da resposta). O vermelho inicial está registrado em
`## Comments`: `E0432` para `pc_lib::items`/`pc_lib::server`, `E0425` para
`load_or_create_token`.

**Critérios.**

1. ✅ por construção, meia parte automatizada. `post_com_token_cria_item_de_texto`
   prova HTTP → lista. A cadeia até a bolha é síncrona e sem fila:
   `post_text` → `push_text` chama os listeners ainda dentro do handler, antes
   do 201 (`items.rs:36-51`) → `handle.emit("item", …)` (`lib.rs:60-64`) →
   `listen("item")` registrado **antes** de `invoke("list_items")`
   (`main.ts:68-69`), com `seen` desduplicando. O "< 1 s" em si ninguém
   cronometrou — débito humano abaixo.
2. ✅ por teste. `post_sem_token_e_recusado_e_nada_entra` e
   `post_com_token_errado_e_recusado_e_nada_entra` sobem `server::spawn` real
   em porta 0, batem por HTTP e conferem 401 + lista vazia. O `emit` só é
   alcançável por `push_text`, que a recusa não atinge.
3. ✅ por construção. `grep -rnE 'innerHTML|insertAdjacentHTML|outerHTML|srcdoc'
   pc/ui pc/index.html` não acha nada; texto entra por `textContent`
   (`main.ts:47,54`) e `href` só recebe URL que `new URL()` aceitou com
   protocolo `http:`/`https:` (`main.ts:17-26`), então nem `javascript:` passa.
4. ✅ por construção, na letra do critério ("um texto que é URL").
   `preventDefault()` + `openUrl(url)` (`main.ts:44-52`); `opener:default` em
   `capabilities/default.json` cobre `allow-open-url`; plugin registrado em
   `lib.rs:99`. O salto para o navegador ninguém viu — débito humano abaixo.
5. ✅ por teste. `dois_posts_preservam_ordem_de_chegada` por HTTP real; os
   listeners rodam dentro do mesmo `Mutex` (`items.rs:37-49`), então a ordem
   dos `emit` é a dos `push`, e `snapshot()` devolve em ordem.
6. ✅ token por teste em disco (`token_e_gerado_uma_vez_e_persiste`;
   `load_or_create_token` só gera quando não há, `config.rs:42-51`). Sessão
   vazia por construção: `Items` é `Arc<Mutex<Vec>>` sem persistência, "Sair"
   é `app.exit(0)` (`lib.rs:88`), o X só esconde (`lib.rs:120-123`). Sair e
   reabrir ninguém fez — débito humano abaixo.

**Achados que saíram deste ticket.** Nenhum é pequeno o bastante para o stage
3 consertar (os dois precisam de critério e teste novos), e nenhum reprova um
critério de SYN-002, então foram para o `## Comments` do ticket dono:

- **SYN-004** — o id de Item ainda é cunhado pelo destinatário
  (`items.rs`, `len()+1`), contra "Cada Item leva um id gerado pelo remetente"
  da spec; confirmação e deduplicação por id não existem, e são pré-requisito
  da fila de Pendentes.
- **SYN-005** — `start_server` só faz `eprintln!` quando o bind em
  `0.0.0.0:47820` ou o token falha: com porta fixa, autostart ligado e X que
  só esconde, a segunda instância vira janela morta e silenciosa. Pede sinal
  na tela e guard de instância única.

**Corrigido aqui (fix pequeno, só no registro).** A linha de protocolo em
`## Comments` ainda dizia `201 com {"id": n}`, que `6918d3a` desfez no mesmo
branch; marcada e corrigida para "201 sem corpo", que é o que `server.rs`
documenta.

**Anotado, não mexido.** Fora do que o ticket pede e sem defeito concreto:
`authorized` compara `t.trim() == token`, tolerando espaço após o valor do
Bearer (mexer em autenticação sem teste novo é justamente o que o stage 3 não
faz); `Items` tem registro de múltiplos listeners para um único assinante;
`Running` guarda só um `SocketAddr` e não permite parar o servidor; o token
viaja como `String` nua; `push_text` chama os listeners segurando o `Mutex`,
então um listener que toque `Items` trava. Nada disso vira `CLEAN-*` agora:
SYN-004 e SYN-005 reescrevem esses mesmos pontos.

**Arquivos.** `pc/src-tauri/src/{config.rs,items.rs,lib.rs,server.rs}`,
`pc/src-tauri/tests/{config.rs,server.rs}`, `pc/src-tauri/Cargo.{toml,lock}`,
`pc/ui/{main.ts,styles.css}`, `pc/index.html` (a linha `<script>` que carrega
`pc/ui/**`, único toque fora dos Primary files, previsto no comentário de
2026-09-17).

Débito humano que sobrevive ao merge (critérios 1, 4 e 6): ver as linhas
`Débito humano:` acima — ninguém rodou o app.
