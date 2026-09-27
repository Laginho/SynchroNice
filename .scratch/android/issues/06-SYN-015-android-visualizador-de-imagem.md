# SYN-015: Android — visualizador de imagem
Stage: blocked
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

## Comments

- 2026-09-26 Stage 2 interrompido: o teste já commitado em `ba8d7f0` precisa corrigir seu preparo. Com gráficos nativos, `SentImageTest` chega a `AppState.queueFile`, mas `scheduleDelivery` lança `IllegalStateException: WorkManager is not initialized properly` antes das asserções. É necessário inicializar explicitamente o WorkManager de teste (dependência work-testing já existe), preservando a costura AppState real + HTTP falso, e repetir a prova vermelha comportamental antes do verde. Não alterei o teste após o commit, conforme ticket-flow. Implementação parcial preservada em `e475559`: prévia Bitmap limitada a 2048px obtida da cópia da fila sob mutex, reconstrução dos Pendentes, visualizador Compose com pinça/arraste/Voltar e imagens recebidas via URI. Não está aprovada: falta gate verde e validação visual. Última execução TestsOnly: 44 testes Rust e 32 Node passaram; app Android 24 testes, 23 passaram e 1 falhou no preparo citado. Para reproduzir neste ambiente: JDK `C:\Users\bruno\.jdks\jdk-17.0.20.1+1`, `JAVA_TOOL_OPTIONS=-Duser.home=C:\Users\bruno`, gate fora do sandbox para carregar o runtime nativo do Robolectric. O user.home temporário falhou com classes Android stub fora do sandbox; no sandbox houve AccessDeniedException no JAR nativeruntime. Computer Use inicializou e listou apps, mas não havia janela Android; adb devices confirmou nenhum dispositivo conectado. O emulador instalado lista SynchroNice_API37, não iniciado nesta etapa. Próximo passo: autorizar a correção do preparo do teste e retomar Stage 2 na branch `syn-015`.

- 2026-09-26 Stage 2: teste `SentImageTest` exercita AppState real, HTTP 201, prévia limitada em RAM, reconstrução de Pendente e ausência de payload/prévia em disco após entrega. Gate TestsOnly vermelho: `ChatMessage.preview` ainda não existe (compileDebugUnitTestKotlin). Ambiente de execução usa JDK 17 e user.home temporário conforme SYN-012.

- 2026-09-26 Proxy decidiu: preservar a imagem enviada apenas como prévia em RAM da Sessão, sem prolongar a cópia de Pendentes nem criar cache em disco. A fila deve continuar removendo seu payload após 201; a bolha mantém a prévia até o processo morrer. Isso permite abrir a imagem entregue e respeita "Guardar fotos tiradas" desligado, que não deixa foto persistente no celular. A prévia dos Pendentes é refeita da fila no início do processo. O teste novo cobre retenção da fonte, não gestos de interface. Android não garante `onDestroy()` quando mata o processo, portanto limpeza de arquivo temporário nesse callback não seria contrato suficiente: https://developer.android.com/guide/components/activities/process-lifecycle.

- 2026-09-26 Attempt 1 failed: exit 0. Log tail: SYN-015 ficou `blocked` na branch `syn-015`, commit `2f88413`. /  / Imagens enviadas são apagadas após a entrega; visualizá-las exige definir retenção e testes no contrato. Nenhum código foi alterado. /  / A [skill ticket-flow](C:/Users/bruno/.agents/skills/ticket-flow/SKILL.md) determina: “Stop only if the ticket needs a seam it does not name”. Registrei a decisão necessária no ticket para Stage 1.

- 2026-09-26 Attempt 2 failed: exit 0; blocked after two attempts. Log tail: SYN-015 ficou `blocked` na branch `syn-015`, commit `50e7c68`. Imagens enviadas são apagadas após a entrega; falta definir retenção ou limitar o ticket às recebidas. /  / A [skill ticket-flow](/C:/Users/bruno/.agents/skills/ticket-flow/SKILL.md) exige: “Stop only if the ticket needs a seam it does not name”. Essa decisão continua fora do contrato. Nenhum código foi alterado.
