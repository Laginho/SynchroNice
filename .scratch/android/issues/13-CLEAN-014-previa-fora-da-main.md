# CLEAN-014: Prévia de imagem Pendente fora da main thread
Stage: done
Status: ready-for-agent
Blocked by: none
Review: agent

- Primary files:
  - android/app/src/main/java/synchronice/android/MainActivity.kt (`AppState`: `init`, `outgoingFile`, construtor; `SynchroNiceApplication.onCreate`)
  - android/app/src/test/java/synchronice/android/SentImageTest.kt

#### What to build

Achado 4 da revisão do PR #7 (sessão `sweatshop/2026-09-25-2127`), separado
do CLEAN-013 porque é outra costura.

**Trabalho pesado na main thread ao iniciar.** O `AppState` nasce no
`Application.onCreate` e ali decodifica a prévia de cada Pendente de imagem,
inclusive quando o processo acorda só para o WorkManager ou para o
Compartilhar. Com muitas fotos Pendentes isso é ANR. A prévia passa a ser
decodificada fora da thread que constrói o `AppState` e aparece na bolha
quando fica pronta: a mensagem nasce com `preview` nulo e é trocada na lista
(pelo `localId`; se a mensagem já saiu da lista, o resultado é descartado).
A troca da mensagem corre na main via `Dispatchers.Main` (os testes trocam por
`Unconfined`), não via `Handler`, senão o `runBlocking` do teste segura o
looper e a prévia nunca chega.

O trabalho de fundo entra por um `Executor` que o construtor recebe com
padrão de uma thread só (`AppState(context, previewExecutor = ...)`); o
`SynchroNiceApplication` usa o padrão, e o teste passa um executor que
enfileira e só roda quando drenado, para a asserção "nulo logo após construir"
não depender de corrida com a decodificação.

A leitura da fila (`queueStore.load()` no `init`) **fica** síncrona. O
achado original também pedia tirá-la da main, mas é um arquivo de texto
pequeno, e todo mundo lê `messages` logo depois de construir (o
`MainActivity.onCreate`, o `hasPending()` do `Application`, os testes). O
custo que vira ANR é a decodificação.

`queueFile` já chama `outgoingFile` dentro de `Dispatchers.IO`; lá a prévia
pode continuar síncrona ou seguir o mesmo caminho, o que der o diff menor.

#### Acceptance criteria

1. Criar o `AppState` com Pendentes de imagem não decodifica nenhuma prévia na
   thread que constrói: logo após o construtor, `preview` é nulo em toda
   mensagem Pendente de imagem.
2. Depois de o executor de fundo rodar, a prévia aparece na bolha da mesma
   mensagem, com o conteúdo da imagem.
3. Um Pendente de imagem cuja prévia ainda não decodificou continua na lista
   como `PENDING`, com nome e `file` preenchidos, igual a hoje.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Tests stage 2 writes (own commit, red)

Dois trechos de `SentImageTest.kt` já existem no commit `7c5ccbf` (branch
`clean-013`, apagada pelo driver; o objeto ainda existe). Não faça cherry-pick
(o commit também traz `ListenLoopTest.kt`, do CLEAN-013); aplique só o trecho
deste arquivo:

    git show 7c5ccbf -- android/app/src/test/java/synchronice/android/SentImageTest.kt | git apply

Se o `apply` não bater, são dois hunks pequenos, para refazer à mão:

- no teste existente (`restored = AppState(context)`), antes das asserções de
  prévia, esperar a prévia de `restored` ficar não nula
  (`withTimeout(5_000) { while (restored.messages.single().preview == null) delay(10) }`);
  fica como está: com o executor padrão, esperar é o certo.
- teste novo `previewForAPendingImageIsDecodedAfterConstructionNotDuringIt`
  (critérios 1 e 2): enfileira um PNG vermelho 64x64, apaga o original,
  constrói outro `AppState`. Ajuste: em vez de esperar com `delay`, passe ao
  construtor um executor que guarda os `Runnable` numa fila; `assertNull` na
  prévia logo após construir; drene a fila; a prévia é vermelha em (0,0).

Vermelho esperado (prova via `gate.ps1 -TestsOnly`): o teste novo falha na
`assertNull` (hoje a prévia já vem decodificada do construtor); o teste
existente segue verde. Commite o vermelho antes de rodar qualquer gate; o
gate nunca em segundo plano (AGENTS.md).

## Comments

- 2026-09-27 Proxy decided: separar o achado 4 do CLEAN-013 neste ticket, e estreitar o critério para a prévia só (a leitura da fila fica síncrona) — os testes de `7c5ccbf` e o `MainActivity.onCreate` leem `messages` logo após construir, então tirar a fila da main mudaria o contrato de todo mundo por um arquivo de texto pequeno; o ANR é a decodificação.
- Débito humano: ANR ao abrir o app com muitas fotos Pendentes num celular real (o teste prova só que o construtor não decodifica; não mede o tempo do `onCreate` no dispositivo).

- 2026-09-27 Attempt 1 stopped to ask: ...rante o download..."). That commit's diff now contains my `previewExecutor`/`schedulePreviewDecode` code mixed into CLEAN-013's listen-loop fix. /  / **Current state:** / - `clean-014` branch: only the red test commit (`f0e8118`), no implementation — safe, untouched. / - `clean-013` branch (currently checked out, HEAD `8dcda31`): contains its own legitimate fix *plus* my leaked CLEAN-014 code. / - Working tree is clean (nothing uncommitted right now). /  / This means a `sweatshop`/`foreman` driver (or another live session) is running against this repo concurrently with me. Continuing to commit here risks tangling the two tickets further or racing another branch switch mid-edit. /  / I'm stopping rather than trying to untangle `8dcda31` myself, since rewriting a commit that another live process may still be building on is the kind of action that's better done with your say-so. Two questions: /  / 1. Is a sweatshop/foreman run active right now? If so, should it be paused before I continue? / 2. Do you want me to split my CLEAN-014 code out of `8dcda31` (rebase/edit that commit), or leave CLEAN-013 as-is and just redo the CLEAN-014 implementation cleanly on `clean-014` once the concurrent process is stopped?

- 2026-09-27 Foreman: o processo concorrente era o proxy do foreman, que por engano rodou comandos git no repo real às 14:05 (checkout de `clean-013`, commit `a4e36ed` com autor `t <t@t>`). Parado e limpo: a branch `clean-013` contaminada (`8dcda31`) foi apagada, nada dela entra. Responda à pergunta 2 assim: refaça a implementação limpa, sem reaproveitar `8dcda31`. O vermelho desta tentativa sobrevive como `f0e8118` (só `SentImageTest.kt`, pinado em `refs/keep/clean-014-red`): `git checkout -b clean-014 sweatshop/2026-09-25-2127`, `git cherry-pick f0e8118`, e siga do verde. A tentativa 1 foi perdida pelo ambiente, não pelo ticket.

#### Resolution (2026-09-27)

Verdict: Needs your call: os três critérios passam, mas a decodificação assíncrona pode perder a corrida com a entrega e deixar a bolha de uma foto já entregue sem prévia, o que antes não acontecia.

- ✅ **Critério 1.** O `init` monta a mensagem com `outgoingFile(id, pending, decodeNow = false)` e manda a decodificação para o `previewExecutor`. O teste com o executor que enfileira vê `preview` nulo logo depois do construtor.
- ✅ **Critério 2.** A troca procura a mensagem pelo `localId` e roda em `runBlocking(Dispatchers.Main.immediate)`. Se a mensagem saiu da lista, o resultado é descartado. O mesmo idioma já existe em `receiveFile`. O teste esvazia a fila e acha vermelho em (0,0).
- ✅ **Critério 3.** No código, sim: `outgoingFile` continua preenchendo nome, `PENDING` e `FileMeta`. Nenhum teste confere esses campos antes da decodificação; o "Tests stage 2 writes" não pedia isso.
- Separação teste-antes-de-código: `7bacc5d` toca só `SentImageTest.kt` e o ticket. `52ed63a` toca só `MainActivity.kt` e o ticket. Nada fora dos Primary files.
- **Proxy decided** (2026-09-27): separar o achado 4 do CLEAN-013 neste ticket, com a leitura da fila (`queueStore.load()`) continuando síncrona. O diff respeita isso.

**Achado que pede decisão (possível regressão, sem correção nesta passada).** O `Application.onCreate` chama `scheduleDelivery` logo depois de construir o `AppState`. Quando a entrega confirma um Pendente, o `PendingQueueStore.kt:79` apaga o arquivo. Se o worker enviar a foto k antes de o executor, que decodifica uma foto por vez, chegar nela, o `decodePreview` pega um `IOException` e devolve `null`. A mensagem continua na lista como `DELIVERED`, e a bolha fica sem prévia até o fim da sessão. Antes da mudança a prévia sempre existia, porque era decodificada antes de qualquer worker rodar. A corrida é mais provável justamente com muitas fotos Pendentes, o caso que este ticket trata, e em rede rápida. O efeito é só visual: nada se perde e nada trava. Corrigir exige uma escolha de desenho (por exemplo, abrir o descritor do arquivo no construtor, ou a entrega esperar a prévia), que é trabalho de stage 1 e não cabe numa correção pequena. Sugestão: um `CLEAN-*` próprio, se valer o custo.

**Standards, sem bloquear (a julgar):**
- A flag `decodeNow` em `outgoingFile`, junto com a checagem `fileMimeType(...).startsWith("image/")` e o `decodePreview(ImageDecoder.createSource(file))`, agora aparece em dois lugares. O ticket permitia qualquer um dos dois caminhos ("o que der o diff menor"), então fica.
- O teste novo repete umas 12 linhas de preparação do teste existente (servidor, pareamento, PNG vermelho, `queueFile`). Um helper resolveria.
- `Executors.newSingleThreadExecutor()` nunca é desligado. Em produção é uma thread por processo. Nos testes, cada `AppState` que decodifica deixa uma thread para trás.

Vermelho-verde, reconferido pelo stage 3. O vermelho registrado pelo stage 2 em `7bacc5d` foi erro de compilação, porque o parâmetro `previewExecutor` ainda não existia, e não a `assertNull` que o ticket previa. O stage 3 então pôs de volta só a decodificação no construtor (`outgoingFile(id, pending)` sem `decodeNow = false`) sobre `52ed63a` e rodou `gate.ps1 -TestsOnly`: 30 testes do app, 1 falha, `previewForAPendingImageIsDecodedAfterConstructionNotDuringIt`, com `AssertionError: the decode must not run on the constructing thread expected null, but was:<android.graphics.Bitmap@…>`. Revertido, o mesmo comando deu 30/30 verdes no app.

Gate completo em `52ed63a`, o mesmo tree de `c83003c`: `gate: passou`. PC 46 Rust (1 + 6 + 39) + 32 node, Android 30 no app + 56 no `protocol-client`, 0 falhas. Um `gate.ps1 -TestsOnly` intermediário teve uma falha instável no PC, fora deste diff (que não toca `pc/`): `reenvio_de_arquivo_com_o_mesmo_id_nao_duplica_item_nem_arquivo` morreu em `tests\server.rs:391` com `ConnectionAborted` (os error 10053). A rodada seguinte passou; o teste está verde nas outras três rodadas.
