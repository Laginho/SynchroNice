# SYN-016: Android — Compartilhar
Stage: to-implement
Status: ready-for-agent
Blocked by: SYN-012
Review: agent

- Primary files:
  - android/app/** (filtros de intent, Activity de entrada sem interface, trabalho do WorkManager, bolhas de envio)
  - android/protocol-client/src/main/kotlin/** (só se a nova tentativa precisar de algo que o cliente não expõe)
  - android/protocol-client/src/test/kotlin/**

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

#### Acceptance criteria

1. Um arquivo enfileirado a partir de uma cópia na pasta interna é enviado e sai da fila, e a cópia interna some depois da confirmação do PC.
2. Com o PC indisponível, o Item continua na fila, e a próxima tentativa o entrega sem duplicar.
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
  que reporta se a fila esvaziou). Os critérios 1 e 2 já podem estar cobertos
  pelos testes de fila existentes. Se estiverem, stage 2 não escreve teste
  nesta costura e aponta no commit quais testes já cobrem.

## Comments

- 2026-09-26 Attempt 1 failed: exit 0. Log tail: O SYN-016 está **bloqueado** no branch `syn-016` (commit `57f3925`). A revisão exige corrigir a duplicação quando o PC grava um Item, mas a resposta HTTP se perde. Isso requer alterar e testar o servidor PC, fora dos arquivos autorizados pelo [ticket](C:/Users/bruno/Desktop/Pastinha/Programas/Projects/SynchroNice/.scratch/android/issues/07-SYN-016-android-compartilhar.md). /  / Registrei a costura necessária no ticket. A etapa 1 precisa autorizar `pc/src-tauri/src/server.rs` e `pc/src-tauri/tests/server.rs`, ou redefinir o critério 2. Os critérios 7 e 8 também seguem pendentes.
