# CLEAN-006: `X-File-Size` mentiroso vira arquivo truncado marcado `done`
Stage: done
Status: ready-for-agent
Blocked by: none
Review: agent

- Primary files:
  - pc/src-tauri/src/server.rs (só `post_file` — o laço que grava o corpo e a
    tarefa de conclusão)
  - pc/src-tauri/tests/server.rs (os testes novos dos dois casos abaixo)

#### What to build

CLEAN-005 fechou a janela de cancelamento do corpo chunked saindo do laço
assim que `received` alcança o `size` prometido. O efeito colateral é que
`post_file` agora confia no `size` do cabeçalho como se fosse verdade, e ele
não é: `X-File-Size` é um número que o celular manda, no caminho chunked, sem
`Content-Length` para o servidor HTTP conferir.

1. **`X-File-Size` menor que o corpo real.** O laço para no `break`, o resto
   do corpo é descartado, e o arquivo truncado é renomeado para o nome final
   e marcado `Done`. A bolha diz "chegou" sobre um arquivo pela metade — o
   pior dos desfechos, porque nada avisa.

2. **`X-File-Size` maior que o corpo real.** O corpo acaba, o laço termina
   por `stream.next()` devolver `None` sem nunca alcançar `size`, e cai no
   mesmo caminho de conclusão: rename + `Done`, de novo sobre um arquivo
   incompleto. Esse caso é anterior a CLEAN-005 (o `while let` sempre
   terminou assim), mas é a mesma pergunta e merece a mesma resposta.

O caminho com `Content-Length` não tem nenhum dos dois: o corpo do axum já
garante que o número de bytes bate com o cabeçalho.

#### Acceptance criteria

1. Um envio chunked cujo corpo entrega **mais** bytes do que o `X-File-Size`
   anunciado não termina em arquivo com nome final marcado `done`: o Item
   fica `Failed` e não sobra `.part` na Pasta.
2. Um envio chunked cujo corpo entrega **menos** bytes do que o `X-File-Size`
   anunciado, e fecha a conexão normalmente (chunk terminador), termina do
   mesmo jeito: Item `Failed`, sem `.part` e sem arquivo com nome final.
3. Um corpo chunked com exatamente `X-File-Size` bytes e o chunk terminador
   termina em `Done`. Sem terminador, fica `Failed`, sem arquivo final nem
   `.part`: o cabeçalho declarado não pode provar que não viriam mais chunks.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Tests stage 2 writes (own commit, red)

- `pc/src-tauri/tests/server.rs`, na costura HTTP: envio chunked com
  `X-File-Size` menor que o corpo — vermelho hoje porque o arquivo aparece
  no nome final, truncado, com o Item `Done`.
- `pc/src-tauri/tests/server.rs`: envio chunked com `X-File-Size` maior que o
  corpo, fechado normalmente — vermelho hoje pela mesma razão.

## Comments

- 2026-09-20 (correção do PR #3 autorizada pelo usuário): critério 3 revisto.
  Aceitar antes do terminador contradizia o critério 1: o servidor não pode
  distinguir um corpo completo de um prefixo cujo próximo chunk ainda não
  chegou. Agora tamanho menor, maior, zero com bytes e ausência de terminador
  falham; tamanho correto com terminador passa. Os quatro casos falharam
  antes da correção e passaram depois via `gate.ps1 -TestsOnly`.
  A conclusão após corpo completo continua em tarefa própria; o caminho
  `Content-Length` conserva a proteção contra cancelamento após o último byte.

- 2026-09-20 (revisão de CLEAN-005): aberto pela revisão. O `break` em
  `received >= size` é consequência direta do critério 1 de CLEAN-005 como
  aprovado, então não é correção pequena lá — precisa de teste novo e de uma
  decisão sobre o que fazer com um `size` que mente.
- 2026-09-20: `Status: needs-triage` de propósito — "o que fazer quando o
  corpo não bate com o `X-File-Size`" tem mais de uma resposta razoável
  (recusar com 400 e marcar `Failed`, aceitar o corpo real e corrigir o
  `size` do Item, ou confiar no cabeçalho como hoje), e escolher é stage 1.
  Os critérios acima escrevem a primeira dessas respostas; confirme ou troque
  antes de mandar para stage 2.

- 2026-09-20 Attempt 1 failed: exit 0. Log tail: ...` with no dedicated `clean-006` branch yet. But it's marked `Status: needs-triage`, and the ticket's own comments flag that stage 1 hasn't actually confirmed the design decision: /  / > "o que fazer quando o corpo não bate com o `X-File-Size`" tem mais de uma resposta razoável (recusar com 400 e marcar `Failed`, aceitar o corpo real e corrigir o `size` do Item, ou confiar no cabeçalho como hoje), e escolher é stage 1. Os critérios acima escrevem a primeira dessas respostas; confirme ou troque antes de mandar para stage 2. /  / Stage 1 is manual per the bindings ("stage 1 é sempre manual, sweatshop nunca roda"), so I'm stopping here rather than dispatching to stage 2 (`tdd`). /  / The criteria as written pick option 1: **reject** — chunked upload with mismatched `X-File-Size` (either too small or too large) marks the Item `Failed` and leaves no `.part` or renamed file. /  / Do you want to: / 1. **Confirm** that behavior (reject on mismatch), or / 2. **Switch** to accepting the real body and correcting the Item's recorded size, or / 3. **Switch** to trusting the header as today (no change)? /  / If you confirm option 1, I'll set `Status` off `needs-triage`, keep `Stage: to-implement`, and proceed to stage 2. /

- 2026-09-20 Attempt 2 failed: exit 0; blocked after two attempts. Log tail: CLEAN-006 is stuck at `Status: needs-triage` — stage 1 hasn't picked how to handle a lying `X-File-Size`, and the ticket's own comments say that choice is stage 1's call, not stage 2's. It reads as three options: /  / 1. **Reject** — mismatched `X-File-Size` (too small or too large) marks the Item `Failed`, no `.part`, no renamed file. (This is what the current acceptance criteria already describe.) / 2. **Accept the real body** — trust the bytes actually received, correct the Item's recorded size instead of trusting the header. / 3. **No change** — keep trusting the header as today (i.e. don't fix CLEAN-006 at all). /  / One more thing: the last `## Comments` line in the ticket file (from the failed attempt, commit `ed1283b`) isn't a normal one-line "Attempt N failed: reason" — it's a garbled multi-paragraph dump of what looks like a previous session's full response, with newlines flattened to `/`. That's not a clean log entry; worth cleaning up regardless of which option you pick. /  / Which option do you want for CLEAN-006 — 1, 2, or 3? I'll fold the answer into the ticket, clean up that comment line, set `Stage: to-implement` with `Status` off `needs-triage`, and hand off to stage 2. /

- 2026-09-23 Review ended at to-review (exit 0); branch clean-006 holds the review; left for a human
- 2026-09-23 Foreman: o review de 11:05 perdeu o veredito porque o daemon do Gradle segurava o pipe do gate (corrigido em c414424, `org.gradle.daemon=false`). Não era pergunta ao humano; de volta a `to-review`.

#### Resolution (2026-09-23)

**Verdict: Approve.**

**Decisão.** Os três critérios estão implementados em `post_file`
(`pc/src-tauri/src/server.rs`) e cada um tem teste no seam HTTP real
(`pc/src-tauri/tests/server.rs`), batendo no servidor por `TcpStream` cru:

- **C1** (corpo maior que o `X-File-Size`): o guarda dentro do laço recusa
  **antes** de gravar o pedaço que estouraria o declarado — 400, o `FailGuard`
  marca `Failed` e apaga o `.part`. Teste
  `chunked_maior_que_tamanho_declarado_falha`, que também assere Pasta vazia.
- **C2** (corpo menor, fechado com terminador): o laço acaba com
  `received != declarado` → 400, mesmo caminho de limpeza. Teste
  `chunked_menor_que_tamanho_declarado_falha`. O caso `size: 0` com bytes tem
  teste próprio (`chunked_com_tamanho_zero_e_bytes_falha`).
- **C3** (exato + terminador → `Done`; sem terminador → `Failed`): o `break`
  ficou condicionado a `fixed_length`, ou seja, só o caminho `Content-Length`
  sai antes do fim do stream. Chunked precisa de `stream.next() == None`.
  Testes `chunked_com_tamanho_correto_termina` e
  `envio_chunked_sem_terminador_falha_mesmo_com_tamanho_prometido`. A proteção
  de CLEAN-005 contra cancelamento depois do último byte segue intacta no
  caminho `Content-Length`.

**Arquivos.** `pc/src-tauri/src/server.rs` (só `post_file`) e
`pc/src-tauri/tests/server.rs` — os dois Primary files, nada fora.

**Correção pequena da revisão** (`132ddc2`): os dois
`expected_size.is_some_and(|size| …)` sombreavam o `size` externo, que é
`expected_size.unwrap_or(0)` com `0` significando *desconhecido* — dois `size`
no mesmo escopo com significados diferentes. O de dentro virou `declarado`.
Dentro dos Primary files, sem teste novo: correção pequena pelo critério
mecânico.

**Prova red-green.** A separação por commit **não existe** aqui (ver o achado
de processo abaixo), então a revisão provou o vermelho à mão: revertendo
cirurgicamente os três guardas de `post_file` para o comportamento
pré-CLEAN-006 (`break` em `expected_size.is_some() && received >= size`, sem o
guarda de estouro e sem o `received != declarado` final) e rodando
`gate.ps1 -TestsOnly`:

    test result: FAILED. 25 passed; 4 failed
      chunked_maior_que_tamanho_declarado_falha
      chunked_menor_que_tamanho_declarado_falha
      chunked_com_tamanho_zero_e_bytes_falha
      envio_chunked_sem_terminador_falha_mesmo_com_tamanho_prometido

Exatamente os quatro testes desta ticket, e só eles. Com os guardas de volta,
29 passed. Os testes chamam o servidor de verdade por HTTP, não uma cópia da
lógica.

**Gate.** `powershell -ExecutionPolicy Bypass -File .\gate.ps1` em primeiro
plano, na branch de sessão com a correção pequena já dentro: `cargo fmt
--check`, `cargo clippy`, `cargo test` **29 passed; 0 failed** (4 + 2 + 29 + 0
nos alvos, unit + integração + doc), `tsc --noEmit`, `gradle check` — `gate:
passou`.

**Achados não corrigidos.**

- **Processo, o único achado duro: `b13d64a` é um commit só para seis
  correções.** O código desta ticket entrou junto com fixes de PR #3 de outros
  tickets — `lib.rs`, `outbox.rs`, `par.rs`, `Cargo.toml`, Kotlin, docs — e
  **sem commit de teste separado, vermelho, antes do código**. A regra que
  sustenta o loop é a separação por `diff --stat`, e dela não sobrou nada no
  histórico. Não é corrigível agora (o commit está mesclado e o usuário
  autorizou esse caminho na época do PR #3); fica registrado porque o próximo
  stage 2 não pode repetir. O vermelho em si está provado acima, à mão.
- **Data clump `expected_size` / `fixed_length` / `size`**: três primitivos
  para um conceito só. Não vale ticket — o conceito tem exatamente um uso.
- **Chunked sem `X-File-Size` nenhum** não é buraco: sem terminador o hyper
  devolve `Err` no stream e cai no 400 (foi o que o teste do terminador provou
  ser o caminho tomado, já que ali `received == declarado`). O único resíduo é
  o Item nascer com `size: 0` na bolha enquanto os bytes chegam — cosmético,
  e o `file_progress` corrige o número exibido.
- **Débito humano:** nenhum. Os três critérios são observáveis por HTTP e
  sistema de arquivos; nada aqui exigia abrir o app.
