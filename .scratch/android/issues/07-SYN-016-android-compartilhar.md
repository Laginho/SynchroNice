# SYN-016: Android — Compartilhar
Stage: to-implement
Status: ready-for-agent
Blocked by: SYN-012
Review: agent

- Primary files:
  - android/app/** (filtros de intent, Activity de entrada sem interface, trabalho do WorkManager, bolhas de envio)
  - android/protocol-client/src/main/kotlin/** (só se a nova tentativa precisar de algo que o cliente não expõe)
  - android/protocol-client/src/test/kotlin/**
  - pc/src-tauri/src/server.rs (só idempotência dos POSTs de texto e arquivo pelo id do remetente)
  - pc/src-tauri/tests/server.rs (só contrato de reenvio com o mesmo id)

#### What to build

Spec: `.scratch/android/spec.md`, histórias 14 a 22, e a seção "App Android"
(Enviar, Compartilhar, O chat mostra). Retoma os critérios adiados de SYN-008
(`.scratch/core/issues/08-*`), sem a confirmação que ele pedia.

O SynchroNice aparece como destino do "Compartilhar" do Android para texto e
qualquer arquivo, um ou vários. A Activity de entrada não tem interface: copia
cada arquivo para a pasta interna do app (a URI do Compartilhar não
sobrevive), enfileira um Item por arquivo ou por texto, e fecha na hora. Não há
confirmação.

O envio roda como trabalho do WorkManager. É acelerado quando curto; quando
longo, vira trabalho em primeiro plano com a notificação de progresso que o
Android exige, e ela some ao terminar. Se a rede falhar ou o PC responder 503,
o Item fica Pendente e o WorkManager tenta de novo, com restrição de rede
conectada, mesmo com o app fechado. Nenhuma outra notificação. 401 segue o
SYN-013 quando ele existir; até lá, o Item fica Pendente.

O texto digitado no composer passa pelo mesmo caminho. Ao abrir o app, o chat
mostra os Itens desta vida do processo e, sempre, os Pendentes da fila,
inclusive os que vieram de um Compartilhar com o app fechado.

Cada Item enfileirado recebe um id do celular, persistido com a fila e enviado
em `X-Item-Id` em toda tentativa. Se o PC já aceitou esse id, outro POST
autenticado responde 201 sem criar outra bolha ou arquivo, mesmo quando a
primeira resposta 201 se perdeu. Um arquivo incompleto ou recusado não consome
o id; Itens diferentes com conteúdo igual mantêm ids diferentes. Requisições
sem `X-Item-Id` preservam o comportamento atual do servidor.

#### Acceptance criteria

1. Um arquivo enfileirado a partir de uma cópia na pasta interna é enviado e sai da fila, e a cópia interna some depois da confirmação do PC.
2. Com o PC indisponível, o Item continua na fila; se o PC o aceita mas a resposta 201 se perde, a próxima tentativa com o mesmo id confirma a entrega sem segunda bolha ou arquivo.
3. Compartilhar uma foto da galeria para o SynchroNice a faz aparecer no PC sem abrir o chat e sem confirmação.
4. Compartilhar texto ou link de outro app o faz aparecer no chat do PC.
5. Compartilhar um PDF, um vídeo ou um áudio o faz chegar na Pasta do PC.
6. Compartilhar várias fotos de uma vez gera um Item por foto no PC.
7. Compartilhar com o PC fora da rede e o app fechado: o Item é entregue sozinho quando o PC volta, sem abrir o app e sem notificação.
8. Um arquivo grande continua indo depois de sair do app, com a notificação de progresso do Android só enquanto dura.
9. Abrir o app depois de compartilhar mostra o Item como bolha à direita, com o estado Pendente ou Entregue.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Critérios 3 a 9 são no aparelho: verificação manual.

## Tests stage 2 writes (own commit, red)

- `android/protocol-client/src/test/kotlin/`: só se o cliente precisar de algo
  novo para a nova tentativa em segundo plano (por exemplo, uma tentativa única
  que reporta se a fila esvaziou). O critério 1 pode já estar coberto pelos
  testes de fila existentes; se estiver, stage 2 aponta quais no commit.
- Na reabertura, teste vermelho no cliente: o mesmo Pendente mantém e envia
  `X-Item-Id` após falha de resposta e após recriar a fila; dois Itens iguais
  recebem ids distintos. Em `pc/src-tauri/tests/server.rs`, repetir POST de
  texto e arquivo com o mesmo id após o primeiro 201 cria um só Item e um só
  arquivo; ids diferentes aceitam conteúdo igual, e envio incompleto pode
  repetir o id. Testes novos em commit próprio antes da correção.
- Para o teste de resposta 201 perdida no envio de texto, usar um `ServerSocket`
  de conexão única: ler o corpo inteiro e `X-Item-Id`, fechar a escuta e então
  encerrar a conexão aceita sem enviar status HTTP. A mesma chamada não pode
  receber 201 por retry automático de `HttpURLConnection`; o Item permanece
  Pendente. Após recriar a fila, um servidor que responde 201 confirma o mesmo
  id e remove o Pendente. Corrigir primeiro o teste em commit próprio, sem
  alterar produção. Manter separado o teste de arquivo grande com 201 antecipado
  e os testes Rust de deduplicação no PC.
- Para a janela de finalização do critério 2, acrescentar teste unitário em
  `pc/src-tauri/src/server.rs` (somente `#[cfg(test)]`): chamar `post_file` real,
  pausar a tarefa destacada após o rename e antes de registrar o id aceito,
  cancelar explicitamente a future do primeiro handler e iniciar outro POST
  com o mesmo id. Confirmar que o segundo passou pela consulta enquanto o
  primeiro ainda finaliza; liberar a pausa e exigir um só Item e arquivo.
  Usar sincronização de teste, sem espera arbitrária nem mudança de comportamento
  de produção. O teste recuperado em `pc/src-tauri/tests/server.rs` continua
  como cobertura de HTTP, mas verde sozinho não prova essa janela.

## Comments

- 2026-09-26 Proxy decidiu: manter o achado da quarta revisão em `syn-016`. O lock do id pertence à future de `post_file`, mas a tarefa destacada continua entre rename e registro do id sem possuir esse lock; se o handler for cancelado nessa janela, outro POST pode passar pela consulta. O teste de socket recuperado em `923e915` fecha a conexão no progresso e não força esse cancelamento, por isso estar verde não refuta o achado. Stage 2 deve primeiro obter o vermelho determinístico descrito acima, em commit só de teste, e depois corrigir a vida útil do lock preservando retry após envio incompleto. Nenhuma mudança de produção foi feita nesta decisão.

- 2026-09-26 Proxy decidiu: o `HttpServer` que fecha sem resposta permite retry transparente do POST pelo `HttpURLConnection`; receber 201 na mesma chamada não representa perda de confirmação. Substituir apenas o cenário de texto pelo servidor TCP de uma conexão descrito acima. O teste de arquivo grande continua separado. Os commits de teste e review existentes ficam preservados; stage 2 retoma a correção de produção após o teste determinístico.

- 2026-09-26 Attempt 1 failed: exit 0. Log tail: O SYN-016 está **bloqueado** no branch `syn-016` (commit `57f3925`). A revisão exige corrigir a duplicação quando o PC grava um Item, mas a resposta HTTP se perde. Isso requer alterar e testar o servidor PC, fora dos arquivos autorizados pelo [ticket](C:/Users/bruno/Desktop/Pastinha/Programas/Projects/SynchroNice/.scratch/android/issues/07-SYN-016-android-compartilhar.md). /  / Registrei a costura necessária no ticket. A etapa 1 precisa autorizar `pc/src-tauri/src/server.rs` e `pc/src-tauri/tests/server.rs`, ou redefinir o critério 2. Os critérios 7 e 8 também seguem pendentes.

- 2026-09-26 Proxy decidiu: ampliar apenas a costura HTTP do PC acima. A fila Android já pertence aos Primary files e deve conservar o id do remetente entre tentativas; só o destinatário consegue confirmar um id já aceito quando o 201 se perde. A deduplicação é por id, nunca pelo conteúdo. Critérios 7 e 8 continuam reabertos para o stage 2.

- 2026-09-26 Attempt 2 failed: exit 0; blocked after two attempts. Log tail: `SYN-016` ficou `Stage: blocked` na [branch/ticket](C:/Users/bruno/Desktop/Pastinha/Programas/Projects/SynchroNice/.scratch/android/issues/07-SYN-016-android-compartilhar.md). /  / O teste HTTP comprometido permite retry automático e não prova resposta perdida. Commits: `2b6d749` e `3b0672e`; branch limpa, sem alterações de produção mantidas. /  / Próximo passo: substituir o cenário por servidor TCP de conexão única e voltar para `to-implement`.

- 2026-09-26 Stage 2 bloqueado: o critério 2 exige deduplicar no destinatário quando o PC grava o Item mas o HTTP 201 se perde. O contrato atual não inclui `pc/src-tauri/src/server.rs` nem `pc/src-tauri/tests/server.rs` nos Primary files ou na costura de testes. Stage 1: autorizar esses arquivos e o teste de reenvio com o mesmo id, ou decidir outro escopo para o critério 2; então voltar a `Stage: to-implement`. Os critérios 7 e 8 continuam pendentes da mesma rodada.

- 2026-09-26 Stage 2: `gate.ps1 -TestsOnly` ficou vermelho por `enqueueText` e `enqueueFile` ausentes; depois da implementação, `gate.ps1` passou (PC 38, Node 24, cliente Android 44, app Android 2 testes).
- Emulador API 37: Compartilhar texto e PDF por URI entregou ao servidor local; a fila e a cópia interna ficaram vazias após confirmação. HTTP 503 deixou texto Pendente e o WorkManager o entregou uma vez no retry sem abrir o chat. As bolhas de texto e PDF apareceram à direita como Entregue. Arquivo de 12 MB ativou notificação de primeiro plano durante o envio e a removeu ao terminar.
- Débito humano: em celular físico com o PC real, conferir foto pela galeria, vídeo e áudio na Pasta do PC, múltiplas fotos como Itens separados e a apresentação da notificação de progresso durante um envio grande.

#### Review (2026-09-26)

Verdict: Reopen — os critérios 2, 7 e 8 precisam de testes e correção.

- Standards: nenhuma violação de regra documentada. Como julgamentos de
  manutenção, `AppState.sendText` virou um caminho de envio sem chamador de
  produção, e `AppState` voltou a interpretar a fila que pertence ao cliente do
  protocolo; não refatorar isso neste ticket.
- ❌ **Critério 2:** o retry não garante "sem duplicar". O cliente só tira o
  Item da fila depois de ler o HTTP 201; se o PC persistir o Item e a resposta
  se perder, a próxima tentativa cria outro Item. A spec raiz exige id gerado
  pelo remetente e deduplicação pelo destinatário. A correção precisa de teste
  novo e alcança o servidor PC, que não está nos Primary files atuais; stage 1
  precisa autorizar essa costura antes da implementação.
- ❌ **Critério 7:** `DeliveryWorker` chama `setForeground` para qualquer
  Pendente grande antes de saber se o PC está acessível. Com o celular conectado
  a uma rede sem o PC, cada tentativa pode mostrar notificação de envio a 0%,
  contrariando a história 20 (Pendente não notifica).
- ❌ **Critério 8:** `large` é calculado antes de adquirir o mutex usado por
  `retryPending`. Um arquivo grande pode entrar entre essa leitura e o início do
  dreno; o mesmo worker então o envia sem trabalho em primeiro plano. Precisa de
  teste de concorrência novo antes da correção.
- Gate: passou `cargo fmt --check`, `cargo clippy`, 38 testes Rust,
  `tsc --noEmit`, 24 testes Node e `gradle check` com testes Kotlin, compilação
  e lint. A primeira tentativa falhou apenas porque o sandbox impediu o AGP de
  usar `C:\.android`; fora dele, o gate passou.

- 2026-09-26 Proxy decidiu: ampliar apenas a costura HTTP do PC para deduplicar pelo id do remetente. Critérios 7 e 8 continuam reabertos para o stage 2.

- 2026-09-26 Stage 2 (reabertura): commit de testes `808d766`, vermelho por
  reenvios com o mesmo `X-Item-Id` criarem três Itens/arquivos em vez de dois
  e pela callback de início real do upload ainda não existir. Cada Pendente
  agora guarda um UUID no disco e o repete em toda tentativa; o servidor PC
  responde 201 a ids já aceitos sem criar outra bolha ou arquivo, e só marca
  arquivo como aceito depois do rename terminar. Upload cortado pode repetir o
  id. O WorkManager entra em primeiro plano a partir do tamanho do arquivo que
  realmente abriu a conexão, sob o dreno serializado: PC inalcançável não
  notifica, e arquivo grande intercalado depois da leitura inicial ainda entra
  em primeiro plano.
- Gate completo verde: `cargo fmt --check`, `cargo clippy`, 41 testes Rust,
  `tsc --noEmit`, 24 testes Node, 48 testes do cliente Android, 2 testes do app
  Android e lint.

#### Review (2026-09-26, segunda revisão)

Verdict: Reopen — critério 9 tem uma corrida e a prova do critério 2 ainda não cobre a perda da resposta.

- Standards: nenhuma violação documentada. Permanecem os julgamentos não
  bloqueantes de manutenção: `AppState.sendText` só tem chamadores nos testes,
  e `hasPending`/`hasLargePending` interpretam a fila pertencente ao cliente.
  Não ampliar esta correção para limpar esses caminhos.
- ❌ **Critério 9:** `AppState.queueFile` libera `outboundMutex` depois de
  enfileirar, antes de adicionar a bolha e seu id a `submitted`. Um worker já
  ativo pode adquirir o mutex, entregar o arquivo e reconciliar a fila nesse
  intervalo. A continuação então registra a bolha como Pendente; o próximo
  `DeliveryWorker` retorna cedo porque a fila está vazia e não a reconcilia.
  O registro da bolha precisa participar da mesma exclusão da reconciliação.
  Stage 2 deve primeiro reproduzir essa intercalação em teste no app, usando
  o caminho de produção de `queueFile`/`retryPending`, e só depois corrigir.
- ❌ **Prova do critério 2:** o teste `id do Pendente sobrevive a falha e
  recriacao da fila` devolve 503 explicitamente. Os testes Rust recebem o 201
  antes de repetir o POST. Nenhum cobre o destinatário aceitar o Item e a
  conexão cair antes da confirmação chegar ao cliente, como exige a seção
  de testes. Cobrir texto e arquivo com resposta perdida, reconstrução da
  fila, manutenção do id e remoção do Pendente após confirmação no retry.
  Incluir arquivo grande: o servidor responde cedo a ids conhecidos enquanto
  o cliente ainda escreve o corpo; a compatibilidade desse caminho precisa
  ser demonstrada (não foi reproduzida falha nesta revisão).
- Critérios 7 e 8: as correções estão presentes; a callback usa o tamanho do
  arquivo efetivamente drenado sob mutex e só ocorre após abrir a conexão.
- Gate desta revisão: passou com 41 testes Rust, 24 Node, 48 do cliente
  Android e 2 do app, além de fmt, clippy, typecheck e lint. Gradle reutilizou
  resultados atualizados. A primeira execução falhou no sandbox por acesso a
  `C:\.android`; a repetição com acesso ampliado passou.
- Sem merge e sem entrada no ledger. Os achados exigem testes novos, por isso
  voltam ao stage 2 conforme `ticket-flow`; nenhuma correção de produção foi
  feita nesta revisão. O débito humano registrado acima permanece.

- 2026-09-26 Stage 2 bloqueado: o teste comprometido `texto aceito sem resposta mantem id ate confirmacao no retry` se provou incorreto. Ao fechar o `HttpServer` sem resposta, `HttpURLConnection` pode repetir o POST automaticamente, receber o 201 na mesma chamada e remover corretamente o Pendente; o teste exige que ele permaneça na fila. Substituir a primeira tentativa por um servidor TCP de uma conexão, que lê o corpo e derruba o socket antes da resposta, prova a perda real sem confundir o retry interno. O teste de arquivo grande continua válido: no retry, o PC responde 201 ao id conhecido sem consumir o corpo e o cliente ainda deixa o Item Pendente. Nenhuma correção de produção foi mantida neste bloqueio.

- 2026-09-26 Stage 2 (segunda reabertura): o commit de teste `95304fe` substituiu apenas o cenário de texto por um servidor TCP de conexão única. O vermelho reproduziu o arquivo grande permanecendo Pendente após 201 antecipado e a bolha de arquivo permanecendo Pendente depois de a fila esvaziar. O retry de arquivo agora lê a resposta HTTP em paralelo ao stream, portanto um 201 idempotente interrompe o reenvio sem confundir queda real com sucesso; `queueFile` registra fila e bolha sob o mesmo `outboundMutex` da reconciliação.
- Gate completo verde: `cargo fmt --check`, `cargo clippy`, 41 testes Rust, `tsc --noEmit`, 24 testes Node, 49 testes do cliente Android, 3 testes do app Android e lint.

#### Review (2026-09-26, terceira revisão)

Verdict: Reopen — critérios 1/8 falham em upload lento e critério 2 tem uma corrida de idempotência.

- Standards: nenhuma violação documentada. Um julgamento não bloqueante de
  manutenção permanece: `AppState.hasPending`/`hasLargePending` interpretam a
  fila do cliente (possível Feature Envy). Não refatorar neste ticket.
- ❌ **Critérios 1 e 8:** `ProtocolClient.kt:184,200-223` começa a ler a
  resposta com timeout de 30 segundos antes de terminar o upload. O servidor
  normalmente só responde depois do corpo completo; uma transferência saudável
  mais longa perde a leitura do 201 e deixa o Item e a cópia interna Pendentes.
  Reprodução contra o JAR de produção atual: servidor HTTP local lê o corpo
  inteiro e responde 201; `enqueueFile` de 1 MiB seguido de `retryPending`,
  pausando 32 segundos só na primeira callback de progresso para simular envio
  lento. Resultado: `PC accepted full body: true; pending after 201: true`.
  Além disso, por inspeção, quando a leitura falha ela não fecha o socket:
  `shutdownOutput` só ocorre após resposta válida e `response.get` só é alcançado
  após `pump`. Se a escrita ficar bloqueada, esse timeout não libera o worker.
  Stage 2: teste vermelho de transferência lenta que termina com 201 e esvazia
  a fila, além de interrupção com escrita bloqueada que devolve o controle para
  retry; preservar a confirmação antecipada de id já aceito.
- ❌ **Critério 2:** `server.rs:350-359,419,507-512` verifica o id antes do
  stream e só o registra depois do rename. Duas requisições sobrepostas podem
  passar pela consulta antes de qualquer registro e criar duas bolhas e dois
  arquivos. Isso também deixa uma janela para um retry enquanto a tarefa
  destacada de flush/rename da tentativa anterior ainda termina. Contraria
  a spec raiz: "reenvio nunca duplica bolha nem arquivo". Achado por inspeção,
  sem reprodução nesta revisão. Stage 2: teste vermelho com dois POSTs de
  arquivo sobrepostos usando o mesmo id, garantindo apenas um Item/arquivo;
  manter o teste de envio incompleto que permite repetir o id.
- As correções anteriores estão presentes: `queueFile` registra fila e bolha
  sob o mesmo mutex da reconciliação; o teste TCP de texto prova a perda de
  resposta; o teste de arquivo grande cobre o 201 antecipado no retry.
- Gate completo passou: fmt, clippy, 41 testes Rust, typecheck, 24 testes Node,
  49 testes do cliente Android, 3 testes do app e lint. Gradle reutilizou
  resultados atualizados. A primeira execução falhou apenas no acesso do
  sandbox a `C:\.android`; a repetição com acesso ampliado passou.
- Sem alterações de produção, merge ou entrada no ledger. Os achados exigem
  testes novos e retornam ao stage 2 conforme `ticket-flow`. O débito humano
  previamente registrado permanece.

- 2026-09-26 Stage 2 (terceira reabertura): o commit de testes `d82ee65`
  reproduziu upload saudável acima de 30 segundos permanecendo Pendente,
  escrita bloqueada sem devolver o worker e dois POSTs simultâneos com o mesmo
  id criando dois Itens/arquivos. A escrita do socket agora tem timeout próprio,
  sem limitar a duração total do upload, e falha de leitura fecha o socket; o
  servidor serializa uploads identificados até o primeiro aceitar ou liberar o
  id após falha.
- Gate completo verde: `cargo fmt --check`, `cargo clippy`, 42 testes Rust,
  `tsc --noEmit`, 24 testes Node, 51 testes do cliente Android, 3 testes do app
  Android e lint.

#### Review (2026-09-26, quarta revisão)

Verdict: Reopen — critério 2 ainda tem uma janela de duplicação após desconexão.

##### Standards

- Nenhuma violação de padrão documentado. Sugestão não bloqueante de limpeza:
  `AppState.sendText` conserva o caminho de envio imediato, mas só tem
  chamadores nos testes; o composer usa `queueText`. Não refatorar neste ticket.

##### Spec

- ❌ **Critério 2:** a spec exige "reenvio nunca duplica bolha nem arquivo".
  Em `pc/src-tauri/src/server.rs:354`, `_item_id_lock` pertence à future de
  `post_file`. A tarefa destacada de flush/rename (`:488`) recebe `_lease` e
  `fail_guard`, mas não o lock do id. Se a conexão cancelar o handler durante
  essa finalização, o lock é liberado antes de `accepted_item_ids.insert`
  (`:521`). Um retry pode passar pela consulta e iniciar outro Item/arquivo.
  Esta é a janela de finalização após desconexão já citada na terceira revisão.
  Achado por inspeção de ownership, sem reprodução executada nesta revisão.
- O teste `envios_concorrentes_do_mesmo_id_criam_um_item_e_um_arquivo` mantém
  ambas as conexões abertas até ler as respostas, portanto só prova a
  sobreposição normal. Stage 2: primeiro reproduzir cancelamento do handler
  durante a finalização e retry do mesmo id; então manter o guarda do id vivo
  também na tarefa destacada (por exemplo, guarda proprietário transferido
  para ela). Preservar o retry após envio incompleto.
- As correções dos critérios 1 e 8 estão presentes: timeout por escrita,
  sem limitar o tempo total do upload saudável, e fechamento do socket na
  falha de leitura. Os testes de upload lento e escrita bloqueada as cobrem.

- Gate completo passou: fmt, clippy, 42 testes Rust, typecheck, 24 testes Node,
  51 testes do cliente Android, 3 testes do app e lint. Gradle reutilizou
  resultados atualizados, com contagens conferidas nos XMLs. A primeira
  execução falhou no acesso do sandbox a `C:\.android`; com acesso ampliado,
  passou.
- Totais: Standards — 0 violações e 1 sugestão não bloqueante; Spec — 1 falha
  no critério 2. A correção exige teste novo e retorna ao stage 2 conforme
  `ticket-flow`. Sem alteração de produção, merge ou entrada no ledger.
  O débito humano previamente registrado permanece.

- 2026-09-26 Stage 2 interrompido: o CLI recebeu bloqueio de conteúdo da API às 16:00. O driver descartou as mudanças sem commit; os ajustes de teste foram recuperados do transcript no commit `923e915`. `gate.ps1 -TestsOnly` passou no ambiente completo, mas o teste corrigido ficou verde sem mudança de produção. A prova vermelha anterior fechava a conexão cedo demais e não reproduzia a corrida de finalização. É preciso obter uma reprodução vermelha válida ou revisar o achado antes de voltar a `to-implement`.

- 2026-09-26 Proxy decidiu: manter o achado da quarta revisão. O lock do id pertence à future de `post_file`, mas a tarefa destacada continua entre rename e registro do id sem possuir esse lock; se o handler for cancelado nessa janela, outro POST pode passar pela consulta. O teste de socket recuperado fecha a conexão no progresso e não força esse cancelamento, por isso estar verde não refuta o achado. Stage 2 deve primeiro obter o vermelho determinístico descrito acima, em commit só de teste, e depois corrigir a vida útil do lock preservando retry após envio incompleto. Nenhuma mudança de produção foi feita nesta decisão.
