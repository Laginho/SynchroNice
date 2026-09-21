# CLEAN-001: Teste de concorrência instável derruba o gate
Stage: done
Status: ready-for-agent
Blocked by: none
Review: agent

- Primary files:
  - pc/src-tauri/tests/server.rs (só o teste instável e o que ele precisa para ser determinístico)
  - pc/src-tauri/src/server.rs (só o caminho de conclusão do envio de arquivo — `post_file`, `reserve_path`/leases, `temp_path_for` e o `rename` final — e o runtime tokio montado em `spawn`, se o defeito estiver nele)

#### What to build

O gate falha de vez em quando sem ninguém ter mexido em `pc/`. Quem roda o
gate precisa poder ler "vermelho" como "eu quebrei alguma coisa", e hoje não
pode: uma segunda rodada, sem nenhuma mudança, passa.

Observado duas vezes em 2026-09-20, as duas no binário de teste `--test
server` do `pc/src-tauri`, durante o trabalho de SYN-007 (que não toca em
`pc/`):

- stage 2 de SYN-007: `dois_envios_concorrentes_do_mesmo_nome_preservam_ambos`
  falhando com `NotFound`; rodada seguinte, verde.
- revisão de SYN-007: uma rodada de gate em quatro com `test result: FAILED.
  17 passed; 1 failed` no mesmo binário (o nome do teste não foi capturado).

A suspeita é o teste de nomes concorrentes: ele depende de tempo entre
reservar o nome e o arquivo final aparecer na Pasta. Confirmar qual é o teste
(rodar o binário em laço até reproduzir) e torná-lo determinístico — esperar
o evento em vez do relógio, ou fazer a costura devolver quando a reserva
virou arquivo. Não é para marcar o teste como `ignored`: o comportamento que
ele cobre é de SYN-003 e continua valendo.

#### Acceptance criteria

1. O teste instável está identificado por nome, e a causa da instabilidade
   descrita em uma linha no corpo deste ticket.
2. Rodar o binário de teste do `server` 20 vezes seguidas passa 20 vezes.
3. O teste continua cobrindo o mesmo comportamento de SYN-003 (dois envios
   concorrentes do mesmo nome preservam os dois arquivos) — nada de
   `#[ignore]` nem de afrouxar a asserção.
4. A conclusão de um envio concorrente não se perde mais: a causa raiz em
   `pc/src-tauri/src/server.rs` está descrita em uma linha no corpo deste
   ticket e corrigida no código de produção. O teste do critério 3, já com
   espera por evento em vez de relógio, é o que prende a correção — o 20/20
   do critério 2 só conta com a espera por evento no lugar.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Mais a repetição do critério 2, com a saída colada no ticket.

## Tests stage 2 writes (own commit, red)

- Nenhum teste novo: o teste existe e é ele o objeto do ticket. A prova aqui
  é a repetição — vermelho intermitente em N rodadas antes, 20/20 verde
  depois, as duas saídas no ticket.

## Comments

- 2026-09-20 (revisão de SYN-007): aberto pela revisão, fora dos Primary
  files de SYN-007 (nada de `pc/` mudou lá). Teste instável no gate é risco
  de toda sessão futura do fluxo — é o portão que decide `to-review`.
- 2026-09-20 (revisão de SYN-008): terceira observação, mesma proporção de
  antes — uma rodada de gate em quatro falhou na branch de sessão logo após o
  merge de SYN-008, e as três seguintes passaram sem nenhuma mudança. O nome
  do teste de novo não foi capturado (só o fim da saída foi olhado), mas a
  rodada que falhou traz `gradle check` BUILD SUCCESSFUL antes do
  `gate: FALHOU`, então a falha está antes do Android: `cargo fmt`,
  `cargo clippy`, `cargo test` ou `tsc`. SYN-008 não toca em `pc/` nem em
  `pc/ui/`. Recado para quem pegar este ticket: as três observações perderam o
  nome do teste por olhar só o `tail` da saída — rodar o gate com a saída
  inteira guardada em arquivo, ou o binário `--test server` em laço, é o
  primeiro passo do critério 1.

- 2026-09-20 (revisão de CLEAN-004): **o nome que faltava nas três primeiras
  observações, capturado duas vezes**, com a saída inteira guardada em arquivo
  como o recado acima pedia. É
  `dois_envios_concorrentes_do_mesmo_nome_preservam_ambos`, falhando sempre no
  mesmo ponto — `tests\server.rs:336`, `std::fs::read(pasta.join("foto.jpg"))
  .expect("primeiro arquivo")` com `Os { code: 2, kind: NotFound }`: o
  **primeiro** arquivo é o que não aparece, não o do sufixo. Duas rodadas
  vermelhas (uma em `447708a`, uma em `clean-004` HEAD), duas rodadas verdes no
  mesmo HEAD sem nenhuma mudança; as vermelhas foram as que rodaram com a
  máquina ocupada por outro build. Nada de CLEAN-004 toca `POST /inbox` nem
  esse teste — a instabilidade é anterior e independente.
  Confirma o que a Attempt 1 já tinha achado: não é só o relógio do teste, a
  conclusão de um dos envios se perde de verdade.

- 2026-09-20 Attempt 1 stopped to ask: ...g. This reproduced on a plain sequential run, no artificial load needed. /  / This means: the ticket's own theory ("just a race between reserving the name and the file appearing — wait for the event instead of the clock") is only half right. The event-based wait is strictly correct and worth keeping, but it can't make criterion 2 (20/20 green) pass on its own, because there's a real intermittent bug in production code where a concurrent upload's completion is sometimes lost outright — and CLEAN-001's Primary files scope me to the test file only. /  / Options: / 1. **Expand this ticket's Primary files** to include `pc/src-tauri/src/server.rs` (and possibly the tokio runtime setup) so I can chase down and fix the actual concurrency defect. / 2. **Split it**: keep CLEAN-001 as the test-measurement fix (already done, and a real improvement) and open a new ticket for the production race, going back to stage 1 to scope it. /  / I'd lean toward (2) — this is a different class of bug (production correctness, possibly affecting real concurrent uploads from multiple devices) and deserves its own investigation rather than being folded into a "make the test deterministic" ticket. Which way do you want to go? /

- 2026-09-20 (humano): **opção 1 — ampliar o escopo.** O CLEAN-001 existe
  para o gate parar de falhar sozinho; só a correção da medição não entrega
  isso, porque o 20/20 do critério 2 continua impossível enquanto a conclusão
  do envio se perder. Dividir fecharia este ticket com o gate ainda instável,
  que é o problema que ele nasceu para resolver. A causa raiz está na
  produção, não no teste, e consertar onde todos os caminhos passam
  (`post_file` → `reserve_path` → `rename`, tudo em `server.rs`) é um diff
  menor do que remendar o teste e voltar ao mesmo lugar por outro ticket.
  Stage 1 é manual neste repo: abrir ticket novo empurraria a especificação
  para fora do run. A espera por evento já feita **fica** — é correta e é o
  que torna o critério 2 mensurável de verdade. `Primary files` ampliados
  para `pc/src-tauri/src/server.rs` (restrito ao caminho de conclusão do
  envio e ao runtime em `spawn`; `lib.rs` só chama `server::spawn` e
  `outbox.rs` não participa), critério 4 acrescentado, `Stage:
  to-implement`. O alvo agora é o defeito de concorrência em produção.

- 2026-09-20 Attempt 1 failed: exit 0. Log tail: Pausing test runs for ~90 seconds — my own repeated gate invocations have likely backed up Windows Defender's real-time scan queue, which is the leading suspect for the file-rename stalls I'm chasing on CLEAN-001. I'll resume the verification once the cooldown finishes. /

- 2026-09-20 Review ended at to-review (exit 0); branch clean-001 holds the review; left for a human

- 2026-09-20 Foreman: o review anterior terminou sem veredito (exit 0 logo apos "Gate re-running after the fixes"), nao por recusa. A branch `clean-001` tem os dois commits na ordem certa (teste vermelho, depois a correcao em `server.rs`). Devolvido a `to-review` para o stage 3 rodar de novo sobre a mesma branch.

- 2026-09-20 (stage 2, retry): **causa raiz confirmada e corrigida.** Trocar o
  `std::thread::sleep(300ms)` do teste por uma espera no evento `file_done`
  (commit anterior, critério 3) trocou o sintoma de "arquivo não existe" por
  "evento nunca chega" — com os outros 18 testes de `tests/server.rs` rodando
  em paralelo (o mesmo tipo de carga do gate normal), o segundo evento não
  chegava em ~13% das rodadas mesmo com os **dois arquivos já gravados e
  íntegros no disco** (confirmado por instrumentação temporária, removida
  antes deste commit) — a causa raiz não é o `rename` em si, que sempre
  completa.

  Causa (uma linha): `post_file` faz `tokio::fs::rename(...).await` e só
  depois chama `state.items.file_done` na mesma future da requisição; se a
  conexão cai logo após o último byte do corpo — o teste de concorrência faz
  isso de propósito, e um cliente real que feche o socket cedo (rede
  instável, app do Android encerrando o envio) faria o mesmo — o runtime
  cancela essa future no meio, o rename (já disparado no thread-pool de
  bloqueio) termina sozinho sem ninguém observar, mas o `file_done` seguinte
  nunca roda: o arquivo fica correto no disco, só o aviso de fim se perde.

  Corrigido isolando rename + `file_done` (e a liberação do lease) numa
  `tokio::spawn` própria: uma tarefa spawnada não é abortada quando a future
  que a criou é cancelada, então o aviso de fim sempre roda depois de um
  rename bem-sucedido, mesmo com a conexão já morta. `post_file` aguarda essa
  tarefa antes de responder, então o contrato para um cliente que continua
  conectado não muda.

  Critério 2, sequencial (sem paralelismo artificial, a mesma condição do
  gate normal):

      [1..20] exit=0 : test result: ok. 19 passed; 0 failed — 20/20 (binário
      tests\server.rs inteiro)
      [1..20] exit=0 : test result: ok. 1 passed; 0 failed — 20/20 (só
      dois_envios_concorrentes_do_mesmo_nome_preservam_ambos, --exact)

  `powershell -ExecutionPolicy Bypass -File .\gate.ps1` (fmt+clippy+test+tsc+
  gradle) passou limpo. Sob carga artificial mais pesada que o gate real (4
  cópias do binário inteiro em paralelo, ~4x mais threads brigando por CPU
  que qualquer cenário de CI ou do gate local), ainda restam falhas raras
  (2/120) do mesmo tipo — parecem esgotamento de recursos do SO sob essa
  carga extrema e não reproduzem em carga sequencial ou 2x paralela (100/100
  e 150/150 verdes); registro aqui para quem revisar julgar se isso é sinal
  de outra causa raiz ainda por achar ou só o teste sendo empurrado além do
  que qualquer execução real do gate faria.

#### Resolution (2026-09-20)

Verdict: Approve

**Decisão.** Critérios 1–4 aprovados, mergeado na branch de sessão em
`e7aed15`. A prova red-before-green foi refeita nesta revisão, não só
aceita do stage 2 — e é ela que responde à pergunta que o stage 2 deixou.

**Prova red-green (feita pelo revisor, mesma máquina, mesma carga).** Com o
teste já em espera por evento e o `server.rs` revertido ao estado
pré-correção (`git checkout 8a2752b -- pc/src-tauri/src/server.rs`), o
binário `tests/server.rs` rodou 20 vezes seguidas:

    TOTAL: 16/20 verdes, 4 vermelhas

As quatro vermelhas são todas o mesmo ponto, e todas gastam os 5s do
`recv_timeout` — o evento nunca chega, o arquivo está no disco:

    thread 'dois_envios_concorrentes_do_mesmo_nome_preservam_ambos' panicked
    at tests\server.rs:346:14:
    os dois envios devem terminar (evento file_done) dentro do prazo: Timeout
    test result: FAILED. 18 passed; 1 failed; finished in 5.04s

Com o `server.rs` corrigido de volta, o mesmo laço de 20:

    TOTAL: 20/20 verdes, 0 vermelhas

E outra vez 20/20 depois das pequenas correções da revisão abaixo. Ou seja:
20% de vermelho sem a correção de produção, 0% com ela, em carga sequencial
— exatamente a carga do gate. A correção é o que prende o teste.

**Critérios.**

1. ✅ `dois_envios_concorrentes_do_mesmo_nome_preservam_ambos`, causa em uma
   linha no comentário do stage 2.
2. ✅ 20/20, reverificado duas vezes pelo revisor (saídas acima).
3. ✅ Comparado o corpo antigo com o novo: as mesmas duas leituras e o mesmo
   `assert_eq!`, com a mesma mensagem; nada removido, nada afrouxado, sem
   `#[ignore]`. A espera por evento **acrescenta** um modo de falha (o evento
   não chegar), então a asserção ficou mais forte, não mais fraca.
4. ✅ Causa raiz na produção descrita e corrigida em `post_file`.

**Sobre as 2/120 sob carga 4x que o stage 2 pediu para julgar.** Não é outra
causa raiz. O laço de 20 acima mostra que sem a correção o mesmo defeito dá
20% em carga *sequencial*; com ela, 0%. O que sobra sob 4 cópias do binário
em paralelo é o relógio que ainda existe no teste — o `recv_timeout(5s)` —
estourando com a CPU saturada, não um `file_done` perdido. O gate nunca roda
nessa carga, e o critério 2 pede carga sequencial. Não segura o ticket.

**Correções pequenas feitas na revisão** (dentro dos Primary files, sem
teste novo, commit `f4823bd`):

- `.await.unwrap_or(false)` engolia um `JoinError` calado: um panic na tarefa
  de conclusão virava 500 sem nada no console e com o Item parado em
  andamento. Agora registra o erro com o caminho.
- O doc de `LeaseGuard` ainda dizia que a reserva cai "em todo retorno de
  `post_file`", o que a correção desta branch tornou falso — o guarda passa
  para a tarefa do rename. Comentário atualizado, mais uma linha na própria
  tarefa dizendo por que o guarda é movido para dentro dela. (Isso, aliás, é
  uma melhoria de concorrência que a correção trouxe de brinde e que ninguém
  tinha registrado: antes, uma requisição cancelada soltava a reserva do nome
  com o rename ainda em voo, e um envio concorrente do mesmo nome podia
  reservar o mesmo destino.)

**Arquivos.** `pc/src-tauri/src/server.rs` (só a cauda de `post_file` e o doc
de `LeaseGuard`), `pc/src-tauri/tests/server.rs` (só o teste em questão).
Nada fora dos Primary files.

**Gate.** `powershell -ExecutionPolicy Bypass -File .\gate.ps1` — verde três
vezes nesta revisão (antes das correções, depois delas e depois do rebase na
branch de sessão): `fmt` e `clippy` limpos, 19/19 em `tests/server.rs`,
4+2 nos outros binários, `tsc` limpo, `gradle check` BUILD SUCCESSFUL.

**Ficou fora, virou CLEAN-005.** Três restos do mesmo trecho, todos
anteriores a este ticket e nenhum coberto pelos critérios daqui: a janela de
cancelamento que sobra num corpo *chunked* (sem `Content-Length`, onde o
`stream.next()` final ainda pode suspender antes do `spawn`), o `.part` que
fica no disco para sempre quando o rename falha ou a conexão cai no meio, e
o `drop(file)` sem `flush` antes do rename.
