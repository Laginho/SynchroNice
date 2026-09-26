# SYN-013: Android — engrenagem e Desparear
Stage: to-review
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

1. A fila de Pendentes do módulo cliente pode ser descartada inteira: depois do descarte, a fila fica vazia em disco e os arquivos copiados para a pasta interna somem.
2. Um descarte que falha na escrita não deixa a memória e o disco discordando (mesma garantia do enfileirar e do desenfileirar).
3. A engrenagem no topo do chat abre a tela de configurações, com "Desparear".
4. "Desparear" apaga o Par, descarta os Pendentes e abre a câmera; ler um QR pareia de novo.
5. Com o PC re-pareado com outro aparelho, a próxima requisição do celular recebe 401, e o app desfaz o Par e abre a câmera sozinho.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Critérios 3 a 5 são no aparelho: verificação manual.

## Tests stage 2 writes (own commit, red)

- `android/protocol-client/src/test/kotlin/`: descartar a fila persistida (fila
  vazia ao recarregar, pasta de cargas vazia), e a falha de escrita no descarte
  sem divergência entre memória e disco. Red porque o descarte não existe.

## Comments

- 2026-09-26 Review anterior parou antes do veredito por autoidentificação incorreta do modelo; o comando CLI usou gpt-5.6-sol high. Retomar stage 3 da branch SYN-013.
