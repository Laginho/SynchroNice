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

- 2026-09-26 Proxy decidiu: preservar a imagem enviada apenas como prévia em RAM da Sessão, sem prolongar a cópia de Pendentes nem criar cache em disco. A fila deve continuar removendo seu payload após 201; a bolha mantém a prévia até o processo morrer. Isso permite abrir a imagem entregue e respeita "Guardar fotos tiradas" desligado, que não deixa foto persistente no celular. A prévia dos Pendentes é refeita da fila no início do processo. O teste novo cobre retenção da fonte, não gestos de interface. Android não garante `onDestroy()` quando mata o processo, portanto limpeza de arquivo temporário nesse callback não seria contrato suficiente: https://developer.android.com/guide/components/activities/process-lifecycle.

- 2026-09-26 Attempt 1 failed: exit 0. Log tail: SYN-015 ficou `blocked` na branch `syn-015`, commit `2f88413`. /  / Imagens enviadas são apagadas após a entrega; visualizá-las exige definir retenção e testes no contrato. Nenhum código foi alterado. /  / A [skill ticket-flow](C:/Users/bruno/.agents/skills/ticket-flow/SKILL.md) determina: “Stop only if the ticket needs a seam it does not name”. Registrei a decisão necessária no ticket para Stage 1.

- 2026-09-26 Attempt 2 failed: exit 0; blocked after two attempts. Log tail: SYN-015 ficou `blocked` na branch `syn-015`, commit `50e7c68`. Imagens enviadas são apagadas após a entrega; falta definir retenção ou limitar o ticket às recebidas. /  / A [skill ticket-flow](/C:/Users/bruno/.agents/skills/ticket-flow/SKILL.md) exige: “Stop only if the ticket needs a seam it does not name”. Essa decisão continua fora do contrato. Nenhum código foi alterado.

- 2026-09-26 Attempt 3 failed: exit 0; blocked after two attempts. Log tail: SYN-015 ficou `blocked` na branch `syn-015`, commit `318d877`. Implementação preservada; 23/24 testes Android passaram. O novo teste precisa inicializar o WorkManager. /  / A [skill ticket-flow](C:/Users/bruno/.agents/skills/ticket-flow/SKILL.md) exige parar quando “a test proves wrong after being committed”. O motivo e os passos para retomar estão no ticket.
