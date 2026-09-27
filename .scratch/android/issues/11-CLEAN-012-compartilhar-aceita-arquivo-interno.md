# CLEAN-012: Compartilhar aceita arquivos internos do próprio app
Stage: to-implement
Status: ready-for-agent
Blocked by: none
Review: agent

- Primary files:
  - android/app/src/main/java/synchronice/android/ShareActivity.kt
  - android/app/src/test/java/synchronice/android/ (teste novo do Compartilhar)

#### What to build

Achado na revisão do PR #7 (sessão `sweatshop/2026-09-25-2127`). Fronteira de
confiança.

O Compartilhar copia para a fila qualquer URI que chegue no Intent, venha de
`EXTRA_STREAM` ou de `clipData`, e o `ShareActivity` é exportado. Um app
qualquer pode mandar ao SynchroNice uma URI `file://` apontando para os
arquivos internos do próprio SynchroNice (as preferências do Par, onde fica o
token, ou a fila), ou uma URI do FileProvider do próprio app. O SynchroNice lê
o arquivo com as permissões dele e o envia ao PC.

A correção: o Compartilhar só aceita URIs `content://` cuja authority não é a
do próprio app. Uma URI recusada é ignorada, e as demais do mesmo Intent seguem
normalmente. Se nenhuma sobra, vale o mesmo caminho de hoje para "nada a
compartilhar". Texto puro (`EXTRA_TEXT`) não muda.

#### Acceptance criteria

1. Uma URI `file://`, inclusive uma que aponte para os arquivos internos do
   app, não entra na fila de Pendentes, e nada do arquivo é lido.
2. Uma URI `content://` com a authority do FileProvider do próprio app não
   entra na fila.
3. Uma URI `content://` de outro provider continua entrando na fila, como hoje.
4. Num `ACTION_SEND_MULTIPLE` com URIs aceitas e recusadas, só as aceitas
   entram na fila.
5. Texto compartilhado continua entrando na fila.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Tests stage 2 writes (own commit, red)

Teste Robolectric do Compartilhar, com Par gravado e a fila observada:
- `file://` para as preferências do Par → fila vazia (critério 1);
- `content://` com a authority do próprio app → fila vazia (critério 2);
- `content://` de um provider de teste → um Pendente de arquivo (critério 3);
- `SEND_MULTIPLE` com as três → só o do provider de teste (critério 4).

## Comments

- 2026-09-27 Criado a partir da revisão do PR #7 (Standards, bug 2). O stage 1
  foi feito na sessão interativa.
