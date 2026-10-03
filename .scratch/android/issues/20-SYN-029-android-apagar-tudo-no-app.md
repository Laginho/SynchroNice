# SYN-029: Apagar tudo no app do celular
Stage: to-implement
Status: ready-for-agent
Blocked by: SYN-027, SYN-028
Review: agent
Difficulty: hard

- Primary files:
  - android/app/src/main/java/synchronice/android/MainActivity.kt (🗑, aviso com Desfazer, Apagar tudo do `AppState`, aplicar a exclusão vinda do PC, id do Item no `ChatMessage`, fechar o visualizador)
  - android/app/src/main/java/synchronice/android/ReceivedFiles.kt (apagar no MediaStore e no SAF)
  - New: android/app/src/test/java/synchronice/android/ApagarTudoTest.kt

#### What to build

O lado do app do Apagar tudo no celular, com o mesmo comportamento e o
protocolo definidos no SYN-019. Leia as decisões de lá, e as do SYN-027, que
já entrega a exclusão do `/listen`, a exclusão na fila, o tirar Pendentes
listados e o id de protocolo de cada Item enviado. Este ticket não mexe no
`protocol-client`.

- **Botão.** Um 🗑 no topo do chat, entre "Na rede" e a ⚙, só visível com o
  chat não vazio.
- **Desfazer.** O toque esvazia o chat na hora e mostra um aviso embaixo:
  "Conversa apagada · N Itens e M arquivos", com "Desfazer" e uma barra que
  esvazia em 5 s. Nada é apagado nem sai pela rede durante a janela.
- **Fim da janela** (o Apagar tudo do `AppState`, que o fim da janela chama):
  - os Pendentes do chat saem da fila e têm a cópia interna apagada. Isso vale
    também para os vindos de um Compartilhar com o app fechado, que o chat
    sempre mostra;
  - os arquivos que o celular gravou ao receber aqueles Itens são apagados de
    vez, no MediaStore ou no SAF. A lixeira do MediaStore exige API 30 e um
    diálogo, e o minSdk é 29;
  - os Itens saem da Sessão;
  - uma exclusão com os ids de todos os Itens apagados entra na fila de
    Pendentes, pelo SYN-027.
- **A cópia de "Guardar fotos tiradas" fica.** Essa opção existe para guardar
  a foto, e o original de quem enviou nunca é tocado.
- **Exclusão vinda do PC** (linha `delete` no `/listen`). As bolhas somem sem
  aviso, os arquivos gravados para aqueles ids são apagados, e os Pendentes
  listados saem da fila. Depois vem o ack.
  - Id desconhecido é ignorado. Uma exclusão que chega depois que o Android
    matou o app não acha nada, e o arquivo fica.
  - Um arquivo ainda baixando é apagado quando o download termina ou falha.
  - Se a imagem aberta no visualizador for apagada, ele fecha.
- **Segurança antes de apagar.** Só a URI que o app gravou para aquele Item, e
  só se o tamanho ainda é o do Item. Na dúvida, não apaga. Um
  `SecurityException` (MediaStore de uma instalação anterior) ou a permissão
  do SAF perdida são ignorados, e o Item sai da Sessão mesmo assim.
- A exclusão na fila nunca vira bolha. Ela ocupa uma posição da fila sem
  bolha: a marcação de Entregue por posição (`submitted`,
  `reconcilePendingDelivery`) tem de contar com isso.
- O `ChatMessage` passa a saber o id de protocolo de cada Item: o UUID dos
  enviados, devolvido pelo cliente (SYN-027), e o `u64` do PC nos recebidos.

#### Acceptance criteria

1. Uma exclusão vinda do `/listen` tira do chat as mensagens com aqueles ids e
   tira da fila os Pendentes listados, e só então o app manda o ack; id
   desconhecido não muda nada e também recebe o ack.
2. O Apagar tudo do `AppState` (a operação que o fim da janela chama)
   esvazia o chat, tira da fila os Pendentes do chat e enfileira uma exclusão
   com os ids de protocolo de todas as mensagens apagadas: o UUID das enviadas
   em `do_celular`, o `u64` das recebidas em `do_pc`.
3. No aparelho: o 🗑 só aparece com o chat não vazio; o toque esvazia o chat e
   mostra o aviso com as contagens; "Desfazer" em até 5 s devolve as bolhas e
   nada sai de `Download/SynchroNice`; sem desfazer, os arquivos recebidos
   saem de lá e o PC perde as bolhas.
4. Um Apagar tudo feito no PC tira as bolhas do celular e os arquivos dele de
   `Download/SynchroNice`.
5. Os testes existentes seguem verdes.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

QA no stage 3: kit no emulador, critérios 3 e 4, conferindo os arquivos com
`phone.ps1 list-files` e o PC pelo CDP; o critério 4 usa o 🗑 do SYN-028.

## Tests stage 2 writes (own commit, red)

- `ApagarTudoTest.kt` (novo), JVM com Robolectric, `AppState` real e servidor
  falso, como `ListenLoopTest` e `PendingDeliveryTest`, critérios 1 e 2.
  Vermelho porque o app ignora a exclusão vinda do PC e o `AppState` não tem
  Apagar tudo.

## Comments

- 2026-10-01 Proxy decided: nasceu do fatiamento do SYN-027 a pedido do dono. Veio para cá o app (🗑, Desfazer, apagar no MediaStore/SAF, exclusão vinda do PC; critérios 6 e 7 do original, aqui 3 e 4), com as decisões de app do original; os critérios 1 e 2 são novos, na costura Robolectric do app que já existe, para este ticket não ficar só com QA. Ficaram no SYN-027 o cliente do protocolo e a fila. Blocked by: SYN-027 (exclusão do `/listen`, fila, id devolvido) e SYN-028, porque o critério 4 precisa do 🗑 do PC e o 3 precisa da janela do PC tirando as bolhas. Difficulty: hard: arquivo ainda baixando, MediaStore e SAF com checagens de segurança, a fila sem bolha mexendo na marcação de Entregue, tudo no `MainActivity.kt`, de que o app inteiro depende. Review: agent, porque as docs ficaram no SYN-019.
- 2026-10-02 Stage 2, commit de testes (`ApagarTudoTest.kt`, 9 testes). A operação do `AppState` que o fim da janela chama chama-se `suspend fun deleteAll(localIds: Set<Long> = todas as bolhas do chat)`: o parâmetro é o conjunto de `localId` da janela, para uma mensagem enviada durante os 5 s ficar de fora. Prova vermelha, duas camadas: (1) sem o método, `gradle test` não compila (`Unresolved reference 'deleteAll'`, 5 usos), o mesmo vermelho do SYN-027 (`29e76b5`); (2) com um stub vazio e temporário de `deleteAll` (não commitado), 81 testes do app rodam e os 9 novos falham, 8 pelo motivo certo (a exclusão do PC nunca recebe ack, o chat não esvazia, o Pendente restaurado ainda é entregue) e `deleteAllOnAnEmptyChatQueuesNothing` passa de propósito: é a guarda do chat vazio, que o código novo não pode quebrar. Lido em volta antes dos testes: `retryPending` conta `queueStore.load().size` como posições de bolha, e uma exclusão na fila (sem bolha, depois de um Pendente enviado na janela) deixa esse Pendente em PENDING (teste `aQueuedDeleteHoldsAQueuePosition...`); o `init` restaura Pendentes como bolhas, que precisam do id de protocolo (teste `deleteAllCoversPendingItemsRestoredAfterTheAppRestarted`).
- 2026-10-02 Stage 2, código (`MainActivity.kt`, `ReceivedFiles.kt`; nenhum teste nem `protocol-client` tocado). Prova vermelho→verde: o commit de testes (`6065582`) não compilava sem `deleteAll`; com o stub vazio eram 81 testes do app, 9 falhando. Com o código: `gate.ps1` completo passou, app 81 testes, 0 falhas (`ApagarTudoTest` 10/10; `ListenLoopTest` 8/8), `protocol-client` e PC como antes (Rust 8+7+5+63, PC/UI 60, QA 14). Como ficou: `ChatMessage` ganha `receivedId` (u64 do PC) e `sentId` (UUID); `AppState.deleteAll(localIds)` e `applyDelete` (exclusão do `/listen`, ack só depois) passam pelo mesmo `dropMessages`, sob `outboundMutex` porque o `pending` do `ProtocolClient` não é thread-safe; `ReceivedFiles.delete(uri, size)` apaga no MediaStore (`resolver.delete`) ou no SAF (`deleteDocument`), só com o tamanho do Item, e ignora `SecurityException`/`IOException`. A janela de Desfazer mora no `AppState` (`window`, `hidden`), não no `ChatScreen`: a ⚙ troca o `ChatScreen` pelo `SettingsScreen` e cancelaria a janela em silêncio. `retryPending` não conta a exclusão da fila como posição de bolha (`submitted`). Download em andamento: o id entra em `deletedFiles` e o `finally` de `receiveFile` apaga o que foi gravado (sem checar tamanho se falhou).
- 2026-10-02 `ListenLoopTest.listenReopensWhileALargeDownloadIsStillInProgress` falhou uma vez numa rodada intermediária (`state.messages.first { it.file != null }`, `ListenLoopTest.kt:117`); passou na rodada do stub, antes, e na rodada final, depois (a do gate completo reaproveitou esse resultado, `UP-TO-DATE`). Não achei ligação causal com a mudança: a bolha do arquivo nasce numa corrotina lançada de forma assíncrona e o teste lê cedo demais. Tratado como flake de timing do teste existente; se reaparecer, é `CLEAN-*` (teste), não deste ticket.
- 2026-10-02 Stage 2, verificações então pendentes: 🗑, aviso com contagens, barra de 5 s, Desfazer e o visualizador fechando não tinham teste automatizado (Compose não está na costura Robolectric do app) e só haviam sido compilados e passados pelo lint; o caminho SAF de `ReceivedFiles.delete` também não, só o MediaStore. O stage 3 verificou os critérios 3 e 4 no emulador, conferindo com `phone.ps1 list-files`: veja o relatório desta revisão abaixo, com os dois ❌ de UI e os passos que passaram. A animação com escala normal e o caminho SAF continuam sem execução; são limites desta validação, e o ticket permanece reaberto. Test tags novas para o roteiro: `clear-all`, `clear-notice`, `undo-clear`, `clear-bar`.
- 2026-10-02 Limites conhecidos, aceitos (nenhum fere um critério): (1) o loop do `listen` espera o `outboundMutex` enquanto um upload está em andamento, então uma exclusão do PC só é aplicada e confirmada depois dele; (2) um texto tocado para sair nos microssegundos entre `addOutgoing` e `queueText` pode ser enviado mesmo com o chat apagado; (3) com a Configuração aberta o `ChatScreen` não está composto, o relógio de 5 s pausa e recomeça ao voltar; (4) um download que termina no mesmo instante da exclusão é coberto pelo `deletedFiles`/`downloading`, mas só por inspeção, sem teste de corrida.

#### Review (2026-10-02)

Verdict: Reopen — critérios 2, 3 e 4, segurança do descarte e falha de persistência.

Revisão completa de `git diff aa4650b...22e453e` e dos três commits, com
Standards e Spec em subagentes independentes; QA dos critérios 3/4 no
emulador. Nenhum código de produção ou teste foi alterado pelo stage 3.
Todos os achados desta passagem estão abaixo; as corridas identificadas
por inspeção não são apresentadas como reproduções de aparelho.

##### Standards

- ❌ **P1 — segurança:** `MainActivity.kt:809` chama `files.delete(uri,
  null)` quando o ACK não foi concluído; `ReceivedFiles.kt:214` pula então
  a proteção de tamanho. O conteúdo pode estar completo/publicado antes
  de o ACK terminar. Exclusão durante ACK lento/falho, seguida de alteração
  do arquivo antes da falha, apaga conteúdo modificado sem conferir tamanho.
  Viola Engineering Workflow, quality gate 1/3 (preservação e segurança).
  O teste de tamanho alterado só cobre entrega já confirmada; o teste de
  download concorrente só cobre término bem-sucedido. É preciso distinguir
  conteúdo completo de ACK aceito e conservar a proteção do primeiro.
- ❌ **P1 — falha operacional, critério 2:** `MainActivity.kt:567-569`
  remove bolhas, referências de `submitted` e recebidos antes de persistir
  a exclusão. `PendingQueueStore.kt:149-155` pode lançar `IOException`;
  `commitClear` (`MainActivity.kt:978-984`) não a trata no escopo da UI.
  A falha pode encerrar o app e deixar Pendentes destinados a envio sem
  suas bolhas/ids disponíveis. Viola quality gate 1/3. Os caminhos existentes
  de texto, composer e câmera tratam erros de persistência. Persistir o
  controle antes da remoção irreversível e tratar a falha recuperavelmente.
- **Observação de limpeza:** o recibo `writing:` de um download já
  interrompido contém a URI parcial que a bolha não contém; `dropMessages`
  não a encontra. Standards identificou inicialmente como lacuna em prosa;
  Spec abaixo a vincula aos critérios 3/4. Não precisa de outro ticket para
  o mesmo defeito.
- Nenhum smell adicional que justifique abstração/refatoração. Primary
  files respeitados: `6065582` contém apenas testes e metadados do ticket;
  `aadb8c2` e `22e453e` não alteram testes nem `protocol-client`.

##### Spec

- ❌ **P2 — critério 3:** “Desfazer em até 5 s”; “sem desfazer, os arquivos
  recebidos saem de lá”. O prazo em `ClearedNotice`
  (`MainActivity.kt:1085-1088`) é cancelado ao desmontar o chat para abrir
  Configurações (`910-911`) e reinicia ao voltar. A exclusão pode ficar
  adiada indefinidamente. **Reproduzido no emulador:** PC conserva três
  Itens e o Desfazer restaura as três bolhas mais de 10 s após Apagar tudo.
- ❌ **P2 — critérios 3/4:** “os arquivos recebidos saem de lá”; “os arquivos
  dele de Download/SynchroNice”. Após download interrompido, a URI fica
  somente no recibo `writing:`, enquanto `ChatMessage.uri` continua nula
  (`MainActivity.kt:704-708,761-764`). `dropMessages:557` não apaga o parcial,
  marca o id apagado e impede retomada pelo `listen:605`. O arquivo e seu
  recibo permanecem. `retryReceivedReceipts:668-670` só retoma `content:`.
- ❌ **P2 — critério 4:** “tira as bolhas do celular”. `listen:605-610`
  registra `inFlightFiles` antes do `launch` que cria a bolha na Main
  (`709-712`). Se a exclusão chegar antes dessa criação, `dropMessages`
  (`549-554`) não encontra bolha nem marca o download já anunciado; o ACK
  sai e o recebimento posterior cria uma bolha FAILED quando o GET retorna
  404 (`795-796`). A exclusão deve alcançar o arquivo já anunciado também
  nesse intervalo, preservando a regra de ignorar ids realmente desconhecidos.
- ❌ **P2 — critério 2:** “ids de protocolo de todas as mensagens apagadas”.
  A bolha criada por `addOutgoing:428-431` ainda não tem UUID. Se a exclusão
  vencer o enfileiramento, `mapNotNull` omite seu id (`569`); `queueText`
  depois enfileira mesmo sem encontrar a bolha (`441-445`). O texto apagado
  chega ao PC. O “limite aceito” (2) do stage 2 não é aprovação do contrato.
- **Observação do contrato:** a proteção de tamanho no ACK falho está
  explícita em prosa, sem critério separado; o eixo Spec a classificou como
  cleanup sem veto. O eixo Standards acima registra o bloqueio de segurança
  e o teste adicional necessário. Nenhum critério foi reescrito.
- Nenhum escopo indevido. A única linha `Proxy decided`, de 2026-10-01,
  mantém app aqui, protocolo/fila no SYN-027, docs no SYN-019 e QA dependente
  do SYN-028. Foi respeitada; ela não aprovou os limites posteriores 2/3.

##### Achado adicional do QA

- ❌ **P2 — critério 3, toque no novo 🗑:** o centro do botão no emulador
  não ativou Apagar tudo em duas tentativas. A captura mostra o cabeçalho
  sob a barra do sistema; a parte inferior do mesmo botão funciona. O
  cabeçalho de `ChatScreen` (`MainActivity.kt:987-1006`) precisa manter a
  área de toque fora da barra. O Voltar de Configurações tem o mesmo
  problema; não é contado como defeito independente.

**Validação:** gate completo em primeiro plano, exit 0, `gate: passou`;
Rust 83 (8 unitários + 7 config + 5 outbox + 63 HTTP), PC/UI 60 e kit QA 14
sem falhas; fmt, Clippy e TypeScript verdes. Android `check` verde, testes
`UP-TO-DATE`: app 81 e cliente 72, zero falhas, erros ou pulos;
`ApagarTudoTest` 10/10. A suíte não cobre os ❌ desta revisão.

**Prova vermelho→verde existente:** conferidos o commit `6065582`, a ausência
de `deleteAll` na produção daquele commit e o registro final real do stage 2
(`.scratch/run-log/SYN-029-implement-20261002-214245.txt.final`): sem API,
falha de compilação; com stub vazio, 9 dos 81 testes falham; implementação,
81/81 verdes. São dez testes novos, com a guarda do chat vazio passando no
stub; o primeiro comentário do stage 2 diz “9 testes” por engano. Esta
revisão não repetiu o vermelho nem introduziu testes de correção.

**QA:** [relatório do emulador](../../qa-runs/2026-10-02-syn-029-review.md).
Desfazer normal em 3.122 ms, contagens, expiração, remoção nos dois aparelhos,
exclusão pelo PC e fechamento do visualizador passaram. Prazo em
Configurações e toque central do botão ❌. Originais preservados, três
recebidos rastreados já ausentes, baseline de dez arquivos anteriores intacto,
`down` concluído. SAF, guardar fotos e animação com escala normal não foram
exercitados. O antigo comentário de débito foi atualizado para apontar a
verificação realizada, sem declarar os ❌ aprovados.

**Para o próximo stage 2, somente os ❌:**

1. Na costura já aprovada `ApagarTudoTest.kt`/`AppState` real/servidor falso,
   acrescentar testes vermelhos próprios para: falha de persistência sem
   perda de bolhas/controle/Pendentes; arquivo completo alterado com ACK
   falho; parcial interrompido apagado e recibo retirado; exclusão entre
   anúncio e criação da bolha; texto apagado antes do enfileiramento; prazo
   que continua quando o chat sai de composição. Usar interleavings
   controlados e código de produção, sem copiar a lógica.
2. Corrigir somente `MainActivity.kt` e `ReceivedFiles.kt`, em commits sem
   testes, inclusive a área de toque do novo botão. Não alterar
   `protocol-client`, critérios ou os testes já verdes sem defeito de harness.
3. Provar os novos vermelhos→verdes, rodar o gate completo em primeiro plano
   e repetir os critérios 3/4 no kit, incluindo toque central e Configurações.
   Devolver `to-review` na mesma branch, sem refazer os fluxos já aprovados.

Totais por eixo: Standards 2 bloqueadores e 1 observação de limpeza;
Spec 4 bloqueadores e 1 observação do contrato; QA 1 bloqueador adicional
de layout, além da confirmação do prazo. `Stage: to-implement` e os dois
relatórios ficam no mesmo commit de reabertura na branch `syn-029`, sem
merge, push ou PR individual; não havia
linha SYN-029 no ledger a retirar.
