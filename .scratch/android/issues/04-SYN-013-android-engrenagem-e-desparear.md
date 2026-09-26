# SYN-013: Android — engrenagem e Desparear
Stage: to-implement
Status: ready-for-agent
Blocked by: SYN-012
Review: agent

- Primary files:
  - android/app/** (engrenagem, tela de configurações, tratamento do 401)
  - android/protocol-client/src/main/kotlin/synchronice/protocolclient/PendingQueueStore.kt (descartar a fila)
  - android/protocol-client/src/main/kotlin/synchronice/protocolclient/ProtocolClient.kt (só se o descarte passar por ele)
  - android/protocol-client/src/test/kotlin/**

#### What to build

Spec: `.scratch/android/spec.md`, histórias 5 a 7, e as seções "Engrenagem" e
"App Android" (token recusado).

Uma engrenagem no topo do chat abre as configurações do app. Os tickets
seguintes (SYN-014, SYN-017, SYN-018) acrescentam opções nela. Este entrega a
tela e o **Desparear**: o app esquece o Par, descarta todos os Pendentes (os
arquivos copiados para a pasta interna saem junto) e abre a câmera. Desparear
não avisa o PC.

Se o PC recusar o token (HTTP 401) em qualquer requisição, o app faz o mesmo
que o Desparear, sozinho. HTTP 503 continua sendo "tentar de novo".

#### Acceptance criteria

1. A fila de Pendentes do módulo cliente pode ser descartada inteira: depois do descarte, a fila fica vazia em disco e os arquivos copiados para a pasta interna somem. Falha ao excluir uma cópia não pode ser tratada como descarte concluído nem perder a referência necessária para nova limpeza.
2. Um descarte que falha na escrita não deixa a memória e o disco discordando (mesma garantia do enfileirar e do desenfileirar).
3. A engrenagem no topo do chat abre a tela de configurações, com "Desparear".
4. "Desparear" apaga o Par, descarta os Pendentes e abre a câmera; ler um QR pareia de novo. Se a gravação que apaga o Par falhar, o Par e os Pendentes antigos permanecem juntos, sem perda parcial.
5. Com o PC re-pareado com outro aparelho, a próxima requisição do celular, inclusive pelo `DeliveryWorker`, recebe 401 e desfaz o Par e os Pendentes. Se o app estiver aberto, volta à câmera; se estiver fechado, a próxima abertura vai à câmera. HTTP 503 mantém Par e Pendentes para retry.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Critérios 3 a 5 são no aparelho: verificação manual.

## Tests stage 2 writes (own commit, red)

- `android/protocol-client/src/test/kotlin/`: descartar a fila persistida (fila
  vazia ao recarregar, pasta de cargas vazia), e a falha de escrita no descarte
  sem divergência entre memória e disco; falha ao excluir uma cópia interna
  não pode produzir sucesso silencioso com arquivo órfão.
- `android/app/src/test/java/` (JVM local/Robolectric): exercitar o caminho real
  de `AppState.unpair` com Par e Pendentes persistidos. Após sucesso, Par, fila
  e cópias internas somem e novo QR pode parear; se a persistência do Par falhar,
  o Par e os Pendentes antigos permanecem juntos. Contra servidor HTTP falso,
  exercitar o caminho usado por `DeliveryWorker`: 401 descarta Par/Pendentes e
  deixa a próxima abertura na câmera, enquanto 503 conserva ambos e retorna
  retry. A verificação manual continua cobrindo câmera, interface e agendamento
  real do WorkManager no aparelho.

## Comments

- 2026-09-26 Proxy decidiu: aprovar a costura de testes JVM locais do app para Desparear e para a política HTTP do `DeliveryWorker`. Esses casos provam os critérios 1, 4 e 5 já exigidos pela spec; não ampliam o produto para testar câmera ou agendamento do WorkManager no aparelho. Falhas de exclusão ou persistência não podem deixar o Par antigo com fila perdida nem declarar sucesso com payload órfão. Stage 2 retoma na branch SYN-013 com teste vermelho em commit próprio antes da correção.

- 2026-09-26 Review anterior parou antes do veredito por autoidentificação incorreta do modelo; o comando CLI usou gpt-5.6-sol high. Retomar stage 3 da branch SYN-013.

- 2026-09-26 Attempt 1 stopped to ask: SYN-013 ficou `blocked`, registrado no commit `cd2a42d`. Nenhum código alterado. /  / A [skill ticket-flow](C:/Users/bruno/.agents/skills/ticket-flow/SKILL.md) exige parar quando falta uma costura de teste aprovada: “a seam the ticket does not name”. Você autoriza incorporar ao contrato testes do Desparear e do HTTP 401/503 no `DeliveryWorker`?
