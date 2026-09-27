# SYN-015: Android — visualizador de imagem
Stage: to-review
Status: ready-for-agent
Blocked by: SYN-014
Review: agent

- Primary files:
  - android/app/** (visualizador e o toque na bolha de imagem)

#### What to build

Spec: `.scratch/android/spec.md`, história 31, e a seção "App Android"
(visualizador).

Tocar numa bolha de imagem, recebida ou enviada, abre a imagem em tela cheia
dentro do app, sem abrir a galeria. Pinça dá zoom, e arrastar move a imagem
ampliada. "Voltar" fecha e volta ao chat. Outros tipos de arquivo continuam
abrindo no app padrão.

Para imagens enviadas, a bolha conserva uma prévia renderizável **em memória
somente nesta Sessão**, obtida da cópia interna enquanto o Item ainda está na
fila. A prévia continua abrível depois do 201 e da exclusão correta da cópia
da fila; isso também vale para um Pendente reconstruído do disco ao iniciar a
Sessão antes de sua entrega. Limitar a decodificação para não carregar um bitmap
sem limite na memória. Não criar arquivo de prévia, URI persistida nem histórico
de entregues. Ao morrer o processo, a prévia e a bolha entregue desaparecem;
um Pendente continua vindo da fila. Fotos da câmera com "Guardar fotos tiradas"
desligado continuam sem cópia em disco após a entrega. Imagens recebidas usam
a URI da Pasta já gravada pelo SYN-014.

#### Acceptance criteria

1. Tocar numa imagem recebida abre a tela cheia dentro do app.
2. Pinça amplia e reduz; arrastar move a imagem ampliada.
3. "Voltar" fecha o visualizador e o chat está como estava.
4. Tocar num arquivo que não é imagem continua abrindo no app padrão.
5. Uma imagem enviada pode ser aberta na bolha enquanto Pendente e depois de Entregue na mesma Sessão, mesmo após a cópia da fila ser apagada. Ao reiniciar o processo, uma imagem entregue não reaparece; uma ainda Pendente reaparece e pode ser aberta sem criar cópia extra em disco.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Gestos e navegação dos critérios 1–4 são de tela: verificação manual. A fonte
da imagem enviada no critério 5 também tem teste JVM abaixo.

## Tests stage 2 writes (own commit, red)

- `android/app/src/test/java/` (JVM local): com `AppState` real e servidor HTTP
  falso, enfileirar imagem, confirmar 201 e verificar que a cópia da fila sumiu
  mas a fonte usada pelo visualizador da bolha enviada ainda é decodificável
  nesta Sessão. Recriar `AppState` não reconstitui imagem já entregue nem
  arquivo de prévia; um Pendente de imagem reconstruído da fila continua
  abrível e permanece abrível após sua entrega. Teste vermelho em commit próprio
  antes da correção. Gestos, tela cheia e navegação continuam na verificação
  manual, conforme a spec.
- No `SentImageTest`, inicializar o WorkManager de teste com
  `WorkManagerTestInitHelper.initializeTestWorkManager(context)` antes de
  `AppState.queueFile`, sem disparar o trabalho agendado: o teste chama
  `retryPending` explicitamente. `work-testing` já é dependência de teste.
  Corrigir só o preparo em novo commit de teste; não simular `scheduleDelivery`
  nem alterar produção para acomodar o teste.

## Comments

- 2026-09-26 Proxy decidiu: o conflito do rebase é documental, neste ticket fora de `Primary files`; não há achado acionável de código nas revisões Standards e Spec. O contrato do stage 2 e seus commits permanecem válidos. Incorporar aqui a prova e o débito humano da branch `syn-015`, preservar também os comentários da sessão e integrar as branches resolvendo somente este Markdown. Retomar o Stage 3 em `to-review`; ele ainda deve verificar o gate e emitir seu próprio veredito, sem considerar esta decisão uma aprovação do código.

- 2026-09-26 Stage 3 bloqueado: o rebase de `syn-015` sobre `sweatshop/2026-09-25-2127` conflita neste arquivo do ticket ao reaplicar `ba8d7f0`; a branch de sessão acrescentou em paralelo `a76272e` e `287e789` ao mesmo contrato/comentários. Como o arquivo do ticket não está em `Primary files`, a regra de merge do `ticket-flow` proíbe o revisor de resolver o conflito. O rebase foi abortado sem alterar a implementação. A revisão de Standards não encontrou violação documentada; a revisão de Spec não deixou achado acionável. É preciso incorporar na branch de sessão o estado final deste ticket (incluindo `Stage: to-review`, prova red-green e débito humano), ou autorizar explicitamente o arquivo do ticket como fronteira de resolução, antes de repetir o Stage 3.

- 2026-09-26 Stage 2 retomado: `ec0f420` corrige somente o preparo do `SentImageTest`, inicializando `WorkManagerTestInitHelper` antes de `queueFile`, sem acionar o trabalho agendado e sem alterar produção. Implementação `e475559` preservada. Prova vermelha: retirar temporariamente os dois arquivos de produção de `e475559` fez `gate.ps1 -TestsOnly` falhar em `compileDebugUnitTestKotlin`, com `Unresolved reference 'preview'` no teste; os arquivos foram restaurados ao HEAD em `finally`. Prova verde: `gate.ps1 -TestsOnly` passou com 24/24 testes do app, 56/56 do protocolo, 44/44 Rust e 32/32 TypeScript. Gate completo passou (fmt, clippy, tsc, testes e Android lint); nenhuma mudança no gate. A primeira execução no sandbox falhou antes dos testes Android por resolver o diretório de usuário como `C:\`; a execução no ambiente normal passou.
- Débito humano: verificar os critérios 1–4 no aparelho (abrir imagem recebida em tela cheia, pinça, arrastar, Voltar preservando o chat e abrir outro arquivo no app padrão), além da abertura visual da imagem enviada do critério 5. Computer Use inicializou e listou a janela do AVD `SynchroNice_API37`, iniciado para esta tentativa; a captura foi recusada com `Computer Use was not approved to use qemu-system-x86_64`. O teste JVM comprova retenção, limite de resolução e descarte da prévia, mas não comprova gestos ou navegação.

- 2026-09-26 Proxy decidiu: a falha 23/24 é só setup do `SentImageTest`: `queueFile` chama `scheduleDelivery`, e o Robolectric lançou `WorkManager is not initialized properly` antes das asserções. Autorizar a inicialização oficial do WorkManager de teste acima. Preservados os commits `ba8d7f0` (teste), `e475559` (implementação) e `318d877` (bloqueio) nesta branch restaurada. Após o ajuste de teste, repetir `gate.ps1 -TestsOnly`; confirmar que a versão sem `e475559` continua vermelha pelo contrato de prévia ausente e que a versão atual passa, então gate completo antes de `to-review`. Nenhum código de produção ou gate foi alterado nesta decisão. Referência: https://developer.android.com/reference/androidx/work/testing/WorkManagerTestInitHelper.

- 2026-09-26 Stage 2 interrompido: o teste já commitado em `ba8d7f0` precisa corrigir seu preparo. Com gráficos nativos, `SentImageTest` chega a `AppState.queueFile`, mas `scheduleDelivery` lança `IllegalStateException: WorkManager is not initialized properly` antes das asserções. É necessário inicializar explicitamente o WorkManager de teste (dependência work-testing já existe), preservando a costura AppState real + HTTP falso, e repetir a prova vermelha comportamental antes do verde. Não alterei o teste após o commit, conforme ticket-flow. Implementação parcial preservada em `e475559`: prévia Bitmap limitada a 2048px obtida da cópia da fila sob mutex, reconstrução dos Pendentes, visualizador Compose com pinça/arraste/Voltar e imagens recebidas via URI. Não está aprovada: falta gate verde e validação visual. Última execução TestsOnly: 44 testes Rust e 32 Node passaram; app Android 24 testes, 23 passaram e 1 falhou no preparo citado. Para reproduzir neste ambiente: JDK `C:\Users\bruno\.jdks\jdk-17.0.20.1+1`, `JAVA_TOOL_OPTIONS=-Duser.home=C:\Users\bruno`, gate fora do sandbox para carregar o runtime nativo do Robolectric. O user.home temporário falhou com classes Android stub fora do sandbox; no sandbox houve AccessDeniedException no JAR nativeruntime. Computer Use inicializou e listou apps, mas não havia janela Android; adb devices confirmou nenhum dispositivo conectado. O emulador instalado lista SynchroNice_API37, não iniciado nesta etapa. Próximo passo: autorizar a correção do preparo do teste e retomar Stage 2 na branch `syn-015`.

- 2026-09-26 Stage 2: teste `SentImageTest` exercita AppState real, HTTP 201, prévia limitada em RAM, reconstrução de Pendente e ausência de payload/prévia em disco após entrega. Gate TestsOnly vermelho: `ChatMessage.preview` ainda não existe (compileDebugUnitTestKotlin). Ambiente de execução usa JDK 17 e user.home temporário conforme SYN-012.

- 2026-09-26 Proxy decidiu: preservar a imagem enviada apenas como prévia em RAM da Sessão, sem prolongar a cópia de Pendentes nem criar cache em disco. A fila deve continuar removendo seu payload após 201; a bolha mantém a prévia até o processo morrer. Isso permite abrir a imagem entregue e respeita "Guardar fotos tiradas" desligado, que não deixa foto persistente no celular. A prévia dos Pendentes é refeita da fila no início do processo. O teste novo cobre retenção da fonte, não gestos de interface. Android não garante `onDestroy()` quando mata o processo, portanto limpeza de arquivo temporário nesse callback não seria contrato suficiente: https://developer.android.com/guide/components/activities/process-lifecycle.

- 2026-09-26 Attempt 1 failed: exit 0. Log tail: SYN-015 ficou `blocked` na branch `syn-015`, commit `2f88413`. /  / Imagens enviadas são apagadas após a entrega; visualizá-las exige definir retenção e testes no contrato. Nenhum código foi alterado. /  / A [skill ticket-flow](C:/Users/bruno/.agents/skills/ticket-flow/SKILL.md) determina: “Stop only if the ticket needs a seam it does not name”. Registrei a decisão necessária no ticket para Stage 1.

- 2026-09-26 Attempt 2 failed: exit 0; blocked after two attempts. Log tail: SYN-015 ficou `blocked` na branch `syn-015`, commit `50e7c68`. Imagens enviadas são apagadas após a entrega; falta definir retenção ou limitar o ticket às recebidas. /  / A [skill ticket-flow](/C:/Users/bruno/.agents/skills/ticket-flow/SKILL.md) exige: “Stop only if the ticket needs a seam it does not name”. Essa decisão continua fora do contrato. Nenhum código foi alterado.

- 2026-09-26 Attempt 3 failed: exit 0; blocked after two attempts. Log tail: SYN-015 ficou `blocked` na branch `syn-015`, commit `318d877`. Implementação preservada; 23/24 testes Android passaram. O novo teste precisa inicializar o WorkManager. /  / A [skill ticket-flow](C:/Users/bruno/.agents/skills/ticket-flow/SKILL.md) exige parar quando “a test proves wrong after being committed”. O motivo e os passos para retomar estão no ticket.

- 2026-09-26 Review ended at blocked (exit 0); branch syn-015 holds the review; left for a human
