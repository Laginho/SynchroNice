# SYN-014: Android recebe arquivos
Stage: done
Status: ready-for-agent
Blocked by: SYN-013
Review: agent

- Primary files:
  - android/app/** (download para a Pasta, bolha de arquivo, abrir, "Mudar pasta…")
  - android/protocol-client/src/main/kotlin/synchronice/protocolclient/ProtocolClient.kt (download para um destino em stream)
  - android/protocol-client/src/test/kotlin/**

#### What to build

Spec: `.scratch/android/spec.md`, histórias 27 a 30, 32 e 33, e a seção
"Pasta". Retoma os critérios adiados de SYN-009 (`.scratch/core/issues/09-*`),
com a mudança da spec nova: arquivo recebido é gravado ao chegar, não com um
toque.

Com o app aberto, cada arquivo anunciado pelo PC é baixado direto para a Pasta
do celular. O padrão é `Download/SynchroNice` via MediaStore (sem permissão,
aparece na galeria). A confirmação ao PC só vai depois de gravado. A bolha à
esquerda mostra progresso animado enquanto baixa. Depois mostra a miniatura, se
for imagem, ou nome, tipo e tamanho. Nome repetido não sobrescreve. Tocar abre
o arquivo no app padrão (o visualizador de imagem embutido é o SYN-015; até lá,
imagem também abre no app padrão).

O módulo cliente passa a baixar para um destino em stream, porque o MediaStore
e o seletor de pastas não dão um arquivo comum. A garantia de hoje continua: a
confirmação só vai depois do download completo.

A engrenagem ganha "Mudar pasta…", que abre o seletor de pastas do Android
(Storage Access Framework). A escolha persiste, e os próximos arquivos vão para
ela.

#### Acceptance criteria

1. O módulo cliente baixa um Item de arquivo para um destino em stream, com o mesmo progresso e a mesma confirmação só depois do download completo que o download para arquivo tem hoje.
2. Um download interrompido não confirma o Item ao PC.
3. ✅ Um arquivo enviado pelo PC com o app aberto é gravado em `Download/SynchroNice` sem nenhum toque e aparece na galeria (se imagem) ou no gerenciador de arquivos. No SAF, o destino fica visível antes de seu primeiro recibo; se o processo morrer nesse intervalo, a retomada não pode criar outra cópia nem sobrescrever um documento alheio. Antes de criar, persistir uma intenção ligada ao Par, id do Item e Pasta, com marcador aleatório exclusivo no nome pedido ao SAF. Só retomar automaticamente quando a consulta à Pasta encontrar exatamente um documento que preserve esse marcador; persistir seu `COLUMN_DOCUMENT_ID`/URI antes de escrever. Não remover o marcador por rename automático. Nome original, tamanho ou posição na listagem não provam identidade. Se o provedor alterar/ocultar o marcador, atrasar a consulta ou houver ambiguidade, manter o Item Pendente e mostrar erro recuperável: não escrever, apagar, criar outra cópia nem confirmar ao PC automaticamente.
4. A bolha mostra progresso animado durante o download, e depois a miniatura (imagem) ou nome, tipo e tamanho (outros).
5. Dois arquivos com o mesmo nome ficam os dois na Pasta, sem sobrescrever.
6. Tocar na bolha abre o arquivo no app padrão.
7. "Mudar pasta…" na engrenagem troca a Pasta; o próximo arquivo vai para a pasta escolhida e a escolha sobrevive a reabrir o app.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Critérios 3 a 7 são no aparelho: verificação manual.

## Tests stage 2 writes (own commit, red)

- `android/protocol-client/src/test/kotlin/`: download para um destino em stream
  contra o servidor falso (bytes iguais, progresso chegando a 100, confirmação
  enviada), e download interrompido sem confirmação. Red porque o download só
  aceita arquivo.
- `android/app/src/test/java/`: com `AppState` real e provedor SAF falso,
  interromper após o provedor criar o documento, antes de devolver a URI.
  Reabrir o app deve reencontrar o documento pelo marcador persistido,
  reaproveitar seu id/URI e terminar com uma cópia, sem sobrescrever outro
  documento de nome original igual. Provedor que muda o marcador ou não
  permite consulta inequívoca deve manter o Item Pendente, sem novo destino,
  escrita ou ACK automático. Pelo laço real de escuta, cobrir também ACK
  bem-sucedido seguido de falha ao remover o recibo: a próxima abertura limpa
  o recibo sem novo GET nem bolha presa em falha, mesmo sem novo anúncio do PC.

## Comments

- 2026-09-26 Proxy decidiu: não assumir que a Pasta SAF fique sem alterações externas. `createDocument` só devolve a identidade após criar, e o provedor pode alterar o nome; recuperar pelo nome comum arriscaria escrever em arquivo alheio. O marcador aleatório persistido antes da criação permite retomar somente um candidato inequívoco. Em provedores que não preservam/expõem esse marcador, falhar fechado e exigir intervenção para escolher outra Pasta ou reconciliar o Pendente; nunca adotar por nome/tamanho nem criar outra cópia às cegas. A limpeza de recibo após ACK continua no escopo. Fontes: https://developer.android.com/reference/android/provider/DocumentsProvider e https://developer.android.com/reference/android/provider/DocumentsContract.Document.

- 2026-09-26 Attempt 1 failed: exit 0. Log tail: SYN-014 ficou `blocked`, commit `5ea7fd9`, sem alterações de código. /  / Falta definir como identificar com segurança o arquivo SAF após crash, antes de receber sua URI. A proposta está no ticket. /  / Interrompi conforme [ticket-flow](C:/Users/bruno/.agents/skills/ticket-flow/SKILL.md): “Stage 2 that needs a decision […] sets `Stage: blocked`”.

- 2026-09-26 Red: gate -TestsOnly falhou na compilação dos dois testes novos porque downloadFile só aceita File e não tem onSaved. Os 44 testes Rust e 24 testes TS passaram; testes JVM existentes do app estavam up-to-date. Primeira tentativa no sandbox falhou por C:\.android; repetição com acesso ao ambiente Android confirmou o red esperado.

#### Stage 2 (2026-09-26)

- Implementado download para OutputStream com fechamento, validação do tamanho e publicação antes do ACK. O caminho File reutiliza essa implementação e mantém o temporário; falhas não confirmam o Item. O app guarda a URI na Sessão após publicar para repetir só o ACK quando necessário.
- Arquivos anunciados são recebidos automaticamente via MediaStore em Download/SynchroNice ou via SAF na Pasta escolhida. Destinos incompletos são removidos após falha; nomes repetidos preservam os arquivos existentes. A escolha da Pasta e a permissão persistente ficam fora do Par.
- Bolhas recebidas mostram progresso animado, miniatura quando imagem, nome/tipo/tamanho e abrem via ACTION_VIEW com acesso temporário de leitura. A engrenagem oferece Mudar pasta….
- Red-green: commit de testes `47eeb37` falha porque a API de stream ainda não existe; com a implementação, os dois testes passam, incluindo interrupção e falhas de fechamento/publicação sem ACK. Nenhum teste foi alterado no commit de implementação.
- Gate completo passou após a última alteração: 44 testes Rust, 24 TS, 56 do protocolo Android e 8 JVM do app; zero falhas. fmt/clippy, typecheck e lint Android passaram.
- Referência de armazenamento: https://developer.android.com/training/data-storage/shared/media (publicação com IS_PENDING) e https://developer.android.com/reference/android/provider/DocumentsContract (SAF).
- Débito humano: verificar critérios 3–7 no aparelho: presença na galeria/gerenciador, progresso e miniatura, nomes repetidos, abertura externa e troca/persistência da Pasta. Não havia aparelho conectado. O emulador SynchroNice_API37 foi iniciado, mas Computer Use recusou a inspeção com `Computer Use was not approved to use qemu-system-x86_64`; nenhuma validação visual foi declarada. Emulador encerrado após a tentativa.

#### Revisão (2026-09-26) — reaberto

Verdict: Reopen — o recebimento pode duplicar um Item depois de reiniciar; `Stage: to-implement`.

**Standards:** nenhuma violação obrigatória. Os nomes genéricos e a limpeza de
Sessão repetida são observações de cheiro, mas refatorá-los seria trabalho fora
do critério que caiu.

**Spec:** o fluxo publica o arquivo e guarda `item.id`/URI apenas na Sessão, em
`ChatMessage` e `receivedFiles`. Se o Android matar o processo depois da
publicação e antes ou durante o `ack`, a Sessão some, o Item continua Pendente no
PC e a próxima abertura baixa outra cópia. Isso contraria a regra retomada de
SYN-009: só o `ack` remove o Item, então um id já gravado precisa ser ignorado.

**❌ Critério 3:** persistir o estado mínimo do Item recebido até o `ack` ter
sucesso, ligado ao Par, para que uma nova `AppState` repita só a confirmação e
não crie outro arquivo. O teste novo deve usar o app real com servidor falso:
publicar o arquivo, falhar o primeiro `ack`, recriar `AppState` com o mesmo
armazenamento e provar que a retomada manda o `ack` sem novo GET nem segunda
cópia. Como exige teste novo, não é conserto de Stage 3.

O achado pequeno do tipo de arquivo foi corrigido em `cc59aa9`: a bolha agora
mostra a extensão (`PDF`) em vez do MIME cru (`application/pdf`). O gate completo
passou depois dessa correção: 44 testes Rust, 24 TS, 56 do protocolo Android e 8
JVM do app; fmt/clippy, typecheck e lint Android passaram. A separação original
continua limpa: `47eeb37` contém só ticket + testes e `0a39464` não toca testes.

#### Retomada Stage 2 (2026-09-26)

- Red: `ReceivedFileRestartTest` usa AppState e cliente HTTP reais, servidor falso e provedor Android de teste com bytes em disco. Após publicação e ACK 503, recria AppState com as mesmas preferências; falha porque fez 2 GETs em vez de 1. `gate.ps1 -TestsOnly`: 9 testes do app, 1 falha; 44 Rust e 24 TS passaram, protocolo sem alterações. A primeira execução no sandbox não alcançou o Android por acesso a `C:\.android`; execução autorizada confirmou a falha comportamental.
- Correção: `MainActivity.kt` grava sincronicamente a URI do Item nas preferências do Par antes de enviar o ACK, indexada por token e id. Uma nova AppState recupera essa URI quando o PC reanuncia o Item e repete apenas o ACK. O recibo é removido após sucesso; trocar/desfazer o Par descarta os recibos, e uma falha ao desfazer restaura os recibos junto do Par. A Sessão continua descartável.
- `ReceivedFiles.kt` só considera a gravação concluída depois de aceitar o recibo: falha ao persistir impede o ACK e aciona a limpeza do destino.
- Green: o teste de `4c89124`, sem alterações, passou com 2 ACKs, 1 GET e 1 cópia; a AppState recriada começa sem histórico. Gate completo passou: 44 Rust, 24 TS, 56 protocolo e 9 app, zero falhas; fmt/clippy, typecheck e lint passaram. Commit de implementação não altera testes.
- O débito visual anterior permanece; esta retomada verifica automaticamente o critério 3 reaberto, sem nova alegação de verificação no aparelho.

#### Revisão (2026-09-26) — reaberto novamente

Verdict: Reopen — o critério 3 ainda cai; `Stage: to-implement`.

**Standards.** O recibo `received-file:<token>:<id> -> URI` acrescenta estado
persistente que o ADR-0002 ainda não documenta: sua lista hoje diz que, fora os
arquivos comuns, persistem apenas Par, Pasta, preferência de cópia e Pendentes.
A persistência é exigida por este ticket; a correção documental fica no
`CLEAN-010`, fora dos Primary files daqui. Os demais achados são cheiros de julgamento
e não reabrem sozinhos: `receivedFiles`/`files` têm nomes pouco específicos; o
prefixo cru `received-file:` se repete; `file`/`uri`/`progress` formam estado
implícito; e `MainActivity.kt` acumula persistência, download e UI. Não
refatorar esses cheiros neste ticket sem que o conserto abaixo exija.

**Spec.**

1. **❌ Critério 3 — a deduplicação ainda tem uma janela de crash.** O
   contrato reaberto exige que uma morte depois da publicação e antes ou
   durante o `ack` retome apenas a confirmação. No caminho MediaStore,
   `ReceivedFiles.receive` limpa `IS_PENDING` antes de o callback de
   `AppState.receiveFile` commitar o recibo. Se o processo morrer entre as duas
   operações, o arquivo já existe e a próxima abertura faz outro GET e outra
   cópia. No SAF, o destino fica visível ainda mais cedo. O teste de
   `4c89124` cobre ACK 503 depois do commit, não essa janela. A próxima rodada
   precisa de um teste no app real que interrompa exatamente essa transição e
   prove uma cópia/um GET após recriar `AppState`.
2. **Limpeza do recibo.** Depois de ACK bem-sucedido, o retorno `false` de
   `commit()` ao remover o recibo é ignorado. A próxima rodada deve provar que
   sucesso não deixa recibo persistente silenciosamente.

O uso de `MediaStore.Downloads` para imagens não é achado: o MediaProvider
resolve `media_type` pelo MIME e a mesma linha entra também na view `Images`.

Nenhum vazamento de escopo foi encontrado. Os caminhos normais de Desparear e
troca de Par removem os recibos. Como os itens 1 e 2 exigem teste novo, nenhum
deles é conserto permitido no Stage 3.

#### Segunda retomada Stage 2 (2026-09-26)

- Red: dois testes no AppState real interrompem a publicação imediatamente antes e depois do update do MediaStore, sem executar limpeza de exceção. Recriar AppState faz 2 GETs em vez de 1 nos dois casos. Gate -TestsOnly: 11 testes do app, 2 falhas; 44 Rust e 24 TS passaram, protocolo sem alterações.
- Red-green da publicação: `2d0c749` contém só testes e ticket; `90670db` grava o recibo antes de publicar e repete a publicação antes do ACK na retomada. Os 11 testes do app passaram, sem alteração dos testes no commit de código.
- Red-green da limpeza: `bdbc13c` prova que commit falso ao remover o recibo era tratado como sucesso (12 testes do app, 1 falha). `fc7ae9f` informa IOException e erro de conexão; a retomada termina a limpeza sem outra cópia. Os 12 testes passaram.
- Red do SAF: `a5b7f53` usa um provedor de documentos com bytes em disco e interrompe antes da escrita ou depois dos bytes completos, antes do recibo final. A versão anterior criava 2 destinos no primeiro caso e fazia 2 GETs no segundo (14 testes do app, 2 falhas). O teste do arquivo incompleto também exige conteúdo completo antes da confirmação.
- Correção do SAF: persiste `writing:<URI>` antes de abrir o stream; depois de fechar e validar o download, troca pelo recibo de URI completa, publica e confirma. Ao retomar uma escrita, lê e conta os bytes do mesmo destino: tamanho exato dispensa outro GET; conteúdo incompleto é regravado no mesmo URI com truncamento. Destinos de downloads interrompidos ficam registrados para a próxima tentativa. A bolha só recebe URI utilizável depois da conclusão. Recibos antigos de URI simples continuam aceitos, e a Sessão continua descartável.
- Green: os cinco testes acrescentados nesta retomada passaram, com uma cópia e um GET em cada cenário de crash. Gate completo passou: 44 Rust, 24 TS, 56 protocolo e 14 app; zero falhas nos relatórios JVM; fmt/clippy, typecheck e lint passaram. A primeira chamada do gate completo terminou com falha apesar de Android verde; a saída truncada não preservou a causa. A repetição sem alterações, com log completo, passou (`%TEMP%/syn-014-gate.log`).
- Referência de API: [ContentResolver.openOutputStream](https://developer.android.com/reference/android/content/ContentResolver) documenta que `w` pode não truncar; a retomada usa `wt`.
- O débito visual já registrado permanece. Esta retomada verifica falhas de processo e armazenamento automaticamente, sem nova alegação de verificação manual no aparelho. A atualização do ADR permanece no CLEAN-010, conforme a revisão anterior.

#### Revisão (2026-09-26) — terceira revisão

Verdict: Reopen — o critério 3 ainda cai; `Stage: to-implement`.

**Standards.** A divergência obrigatória entre os recibos persistentes e a
lista exaustiva do ADR-0002 permanece, mas já tem dono no CLEAN-010, bloqueado
por este ticket. Os demais achados são cheiros de julgamento e não devem ser
refatorados aqui: recibo codificado em `String`, prefixo `received-file:`
repetido, nomes genéricos em `receivedFiles`/`files`, estado de arquivo espalhado
por `file`/`uri`/`progress` e flags booleanas nos cenários do teste.

**Spec.**

1. **❌ Critério 3 — o SAF ainda tem uma janela de duplicação.**
   `DocumentsContract.createDocument` torna o arquivo visível antes de
   `ReceivedFiles.receive` chamar `onCreated`, que é quando `AppState` persiste
   `writing:<URI>`. Uma morte entre o retorno do provedor e esse commit deixa um
   arquivo sem recibo; a nova `AppState` cria outro. O teste "before SAF write"
   interrompe dentro de `openFile`, portanto depois de `onCreated`, e não cobre
   essa janela. A próxima rodada precisa matar o provedor logo depois de criar o
   documento, antes de devolver a URI, e provar uma só cópia após a retomada.
2. **A limpeza depois do ACK não é retomável no fluxo real.** Se o `ack`
   funciona e o `commit()` que remove o recibo falha, o PC já retirou o Item e
   `/listen` não o anuncia novamente. O teste atual mascara isso chamando
   `receiveFile` diretamente na `AppState` reaberta. A próxima rodada deve usar
   o laço real de escuta e provar que uma falha de remoção não deixa recibo
   órfão nem bolha permanentemente em falha.

Os dois achados exigem teste novo, portanto não são consertos permitidos no
Stage 3. Nenhum vazamento de escopo novo foi encontrado. Os pares de commits de
teste/código continuam separados e `git diff --check` passou.

Gate completo: a primeira chamada parou apenas porque o sandbox não podia
gravar em `C:\.android`; repetido com acesso ao ambiente Android, passou com 44
testes Rust, 24 Node e `gradle check` verde (56 testes do protocolo e 14 do app
nos relatórios), além de fmt, clippy, typecheck e lint.

#### Stage 2 (2026-09-26) — decisão necessária sobre recuperação SAF

- Inspeção: `ReceivedFiles.receive` cria o documento antes de `onCreated`;
  `AppState.receiveFile` só então persiste sua URI. A terceira revisão identifica
  corretamente essa janela. Ainda não houve alteração de código ou testes.
- A API `DocumentsContract.createDocument` recebe Pasta, MIME e nome, e só
  devolve a identidade atribuída pelo provedor depois da criação. Não oferece
  chave de idempotência nem transação conjunta com as preferências do app.
  `COLUMN_DISPLAY_NAME` é um título; a identidade é `COLUMN_DOCUMENT_ID`.
  Referências: https://developer.android.com/reference/android/provider/DocumentsContract
  e https://developer.android.com/reference/android/provider/DocumentsContract.Document.
- Inferência: uma intenção persistida antes da chamada permite procurar o
  nome escolhido depois do crash, mas não prova que um documento com esse nome
  foi criado por esta tentativa. Outro escritor pode criá-lo no intervalo;
  retomá-lo com `wt` pode sobrescrever um arquivo alheio. Um marcador no nome
  diminui essa ambiguidade, mas altera a convenção de nomes e precisa de um
  contrato para provedores que ajustam nomes. Renomear também pode trocar a URI.
- **Pergunta para Stage 1:** podemos limitar a recuperação automática SAF a
  provedores que preservam o nome solicitado e tornam a criação imediatamente
  consultável, assumindo ausência de alterações externas na Pasta durante essa
  operação? Nesse contrato, a solução mínima é persistir Pasta/nome reservado
  antes de criar, recuperar sua URI por consulta e retomar o mesmo destino.
  Se não, definir a identidade recuperável permitida (por exemplo, marcador
  visível no nome) e o comportamento diante de recuperação ambígua, sem
  sobrescrever nem confirmar um arquivo que não se pode identificar.
- A limpeza após ACK segue pendente e deve ser coberta pelo laço real de
  escuta, como exigido na terceira revisão. Não foi corrigida nesta sessão:
  `ticket-flow` determina interromper Stage 2 quando falta decisão no contrato.
- Sem gate nesta sessão: apenas registro de bloqueio; nenhuma nova alegação
  de red-green ou validação visual. Retomar na branch `syn-014` após incorporar
  a decisão ao contrato e voltar a `Stage: to-implement`.

- 2026-09-26 Proxy decidiu: não assumir que a Pasta SAF fique sem alterações externas. `createDocument` só devolve a identidade após criar, e o provedor pode alterar o nome; recuperar pelo nome comum arriscaria escrever em arquivo alheio. O marcador aleatório persistido antes da criação permite retomar somente um candidato inequívoco. Em provedores que não preservam/expõem esse marcador, falhar fechado e exigir intervenção para escolher outra Pasta ou reconciliar o Pendente; nunca adotar por nome/tamanho nem criar outra cópia às cegas. A limpeza de recibo após ACK continua no escopo. Fontes: https://developer.android.com/reference/android/provider/DocumentsProvider e https://developer.android.com/reference/android/provider/DocumentsContract.Document.

#### Terceira retomada Stage 2 (2026-09-26)

- Red SAF: teste no AppState real interrompe o provedor após criar, antes de devolver a URI. A retomada cria 2 documentos em vez de 1. Gate -TestsOnly: 15 testes do app, 1 falha; 44 Rust, 24 TS e 56 protocolo passaram. Primeira tentativa parou por acesso a C:\.android; repetição autorizada confirmou a falha comportamental.

- Red SAF ampliado: 20 testes do app, 6 falhas contra o código anterior. Marcador alterado/oculto/ambíguo e consulta carregando não interrompem a recuperação; consulta indisponível não preserva intenção; mudar a preferência de Pasta faz consultar o lugar errado. Os testes incluem documento alheio com nome original igual.

- Green SAF: 20 testes do app passaram com a intenção persistida antes da criação e consulta por marcador na Pasta original. Testes cdc1ebe/5b9103e; código 9501a89, sem alteração de testes.
- Red da escuta: o novo teste exige executar o laço real via AppState.listen; o gate falha na compilação porque esse laço ainda está embutido no Composable. O servidor deixa de anunciar o Item após ACK; a falha de remoção também reproduz a alteração do cache de SharedPreferences antes de retornar false.

- Red comportamental da escuta: após expor o laço de produção sem mudar seu comportamento, o teste cee19af falhou porque ACK aceito + falha de limpeza deixou a bolha FAILED em vez de DELIVERED (21 testes do app, 1 falha).
- Green da escuta: AppState executa o mesmo laço usado pela Activity e, antes de abrir /listen, republica/confirma os recibos completos do Par e tenta removê-los. Isso independe de novo anúncio do PC e não recria o histórico. Se commit(false) já alterou o cache de SharedPreferences, o recibo é restaurado para permitir nova tentativa. ACK aceito deixa a bolha Entregue mesmo quando a limpeza falha. O teste commitado passou, com um GET e uma cópia; o servidor não reanuncia após ACK.
- Recuperação SAF: a intenção contém Pasta original e marcador UUID e fica no recibo do token/id antes de createDocument. A consulta exige um único documento com o marcador e sem EXTRA_LOADING; o document ID vira URI persistida antes da escrita. Não há rename automático. Provedor que oculta/altera o marcador, devolve candidatos ambíguos ou não permite consulta deixa o Item Pendente com erro recuperável, sem criar, escrever, apagar ou confirmar na retomada. Documento alheio com o mesmo nome original é preservado, inclusive ao trocar a preferência de Pasta entre as duas AppState.
- Gate completo passou: 44 testes Rust, 24 TypeScript, 56 protocolo Android e 21 JVM do app (145 no total), zero falhas; fmt/clippy, typecheck e lint Android passaram. Log: `%TEMP%/syn-014-final-gate.log`. `git diff --check` passou. Os commits de implementação não alteram testes.
- A referência para identidade e consulta SAF continua sendo DocumentsContract.Document: https://developer.android.com/reference/android/provider/DocumentsContract.Document. A intenção limita a recuperação aos provedores que expõem seu marcador; não pressupõe transação oferecida pelo Android.
- O débito visual anteriormente registrado permanece; esta retomada validou automaticamente as falhas de processo e persistência, sem declarar nova verificação no aparelho. CLEAN-010 continua responsável pela atualização do ADR. Stage 2 encerrado; revisão é uma sessão separada.

#### Revisão (2026-09-26) — reaberto pela quarta vez

Verdict: Reopen — o fluxo SAF normal pode ficar Pendente sem necessidade; `Stage: to-implement`.

**Standards.** A persistência dos recibos ainda contradiz a lista exaustiva do
ADR-0002, com correção já isolada no CLEAN-010. Os demais achados são cheiros
de julgamento, não motivos para ampliar este ticket: estados e chaves de recibo
codificados em `String`, nomes genéricos em `receivedFiles`/`files` e
responsabilidades acumuladas em `MainActivity.kt`.

**Spec.**

1. **❌ Critério 3 — a recuperação por marcador foi aplicada também à criação
   normal.** Em `ReceivedFiles.receive`, uma chamada nova a
   `DocumentsContract.createDocument` descarta a URI inequívoca devolvida pelo
   provedor e sempre tenta reencontrar o documento pelo marcador. Um provedor
   que normalize o nome ou demore a expor a listagem deixa o Item Pendente mesmo
   sem morte do processo, embora a identidade já seja conhecida. A consulta
   fail-closed é necessária somente ao retomar uma intenção `creating:` sem
   URI. A próxima rodada precisa testar um recebimento SAF ininterrupto cujo
   provedor devolve uma URI válida, mas altera/oculta o marcador na listagem, e
   provar que essa URI é persistida antes da escrita e o download conclui. A
   recuperação depois de crash continua exigindo consulta inequívoca pelo
   marcador e deve permanecer fail-closed.

O achado exige teste novo, portanto não é conserto permitido no Stage 3.
Nenhum arquivo de produção ou teste foi alterado nesta revisão; o gate não foi
repetido porque a branch continua no mesmo commit de código já validado pelo
Stage 2.

#### Quarta retomada Stage 2 (2026-09-26)

- Red: o teste uninterruptedSafCreationUsesReturnedUriEvenWhenProviderChangesMarker usa AppState real e provedor SAF que devolve URI válida, mas normaliza o nome. Exige recibo da URI antes de abrir o destino, bytes completos e ACK. gate -TestsOnly falhou na consulta por marcador em ReceivedFiles.kt:109: 22 testes do app, 1 falha. Log: %TEMP%/syn-014-normal-red.log.

- Green: criação SAF normal usa diretamente a URI retornada por createDocument; apenas uma intenção creating: recuperada sem URI consulta o marcador. O callback existente persiste writing:URI antes da abertura do stream. Teste vermelho no commit 595dbf4, sem alterações no commit de código.
- Gate completo passou: 44 Rust, 24 TypeScript, 56 protocolo Android e 22 app (146 testes), zero falhas; fmt/clippy, typecheck e lint passaram. Recuperação após crash e consultas ambíguas continuam cobertas. Log: %TEMP%/syn-014-normal-green.log. git diff --check passou.
- Os débitos visuais anteriores permanecem; esta rodada corrige apenas o achado da quarta revisão. Stage 2 encerrado em to-review; revisão em sessão separada.

#### Resolution (2026-09-26)

Verdict: Approve

- **Standards:** a persistência transitória dos recibos ainda não consta na
  lista exaustiva do ADR-0002; o achado está isolado no `CLEAN-010`, fora dos
  Primary files deste ticket. Nenhuma outra violação documentada. Os nomes
  genéricos, estados codificados em `String`, helper de teste parametrizado e a
  concentração de responsabilidades em `MainActivity.kt` são cheiros de
  julgamento e não justificam refatoração aqui.
- **Spec:** nenhum achado. A criação SAF normal usa a URI devolvida e persiste
  `writing:<URI>` antes de abrir o stream; somente a retomada de `creating:`
  consulta o marcador e permanece fail-closed para zero/múltiplos candidatos,
  consulta carregando ou indisponível. Os sete critérios estão implementados.
- **Red-green:** `9687784` prova que um provedor que normaliza o nome ainda deve
  concluir a criação normal; o teste falhou na consulta indevida do marcador.
  `5bc2762` passou a usar diretamente a URI retornada, sem alterar testes. As
  rodadas anteriores registram as provas de stream, interrupção, crash,
  deduplicação SAF e limpeza de recibos pelo laço real de escuta.
- **Gate:** após rebase e integração com os Copiáveis da branch de sessão,
  `gate.ps1` passou: fmt/clippy, 44 testes Rust, typecheck, 32 testes Node, 56
  testes do protocolo Android e 23 do app, compilação e lint; zero falhas.
- **Verificação visual:** permanece o `Débito humano` dos critérios 3–7 para
  o QA conjunto no aparelho, conforme a regra do repositório.

- 2026-09-27 Humano (revisão do PR 7): o nome final no SAF é o do PC. Com o
  conteúdo completo, o documento é renomeado sem o marcador, e colisão fica
  com o `(1)` do provedor; isto substitui a frase "Não remover o marcador
  por rename automático" do critério 3, que contrariava a spec ("Nome
  repetido recebe sufixo, como no PC"). Um recibo `renaming:` gravado antes
  do rename deixa a retomada terminar e confirmar sem outra cópia. "Mudar
  pasta…" passa a ser a intervenção que o critério 3 pede: recomeça na Pasta
  escolhida os recebimentos que não chegaram a ter conteúdo completo. Destino
  de recibo `writing:` que sumiu recomeça sozinho, porque a URI o identifica
  sem ambiguidade.

- Débito humano: numa Pasta SAF real (Downloads, pendrive, Drive), o arquivo recebido termina com o nome limpo, e um segundo de mesmo nome vira `(1)` (revisão do PR 7).
