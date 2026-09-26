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

## Comments

- 2026-09-26 Proxy decidiu: o `HttpServer` que fecha sem resposta permite retry transparente do POST pelo `HttpURLConnection`; receber 201 na mesma chamada não representa perda de confirmação. Substituir apenas o cenário de texto pelo servidor TCP de uma conexão descrito acima. O teste de arquivo grande continua separado. Os commits de teste e review existentes ficam preservados; stage 2 retoma a correção de produção após o teste determinístico.

- 2026-09-26 Attempt 1 failed: exit 0. Log tail: O SYN-016 está **bloqueado** no branch `syn-016` (commit `57f3925`). A revisão exige corrigir a duplicação quando o PC grava um Item, mas a resposta HTTP se perde. Isso requer alterar e testar o servidor PC, fora dos arquivos autorizados pelo [ticket](C:/Users/bruno/Desktop/Pastinha/Programas/Projects/SynchroNice/.scratch/android/issues/07-SYN-016-android-compartilhar.md). /  / Registrei a costura necessária no ticket. A etapa 1 precisa autorizar `pc/src-tauri/src/server.rs` e `pc/src-tauri/tests/server.rs`, ou redefinir o critério 2. Os critérios 7 e 8 também seguem pendentes.

- 2026-09-26 Proxy decidiu: ampliar apenas a costura HTTP do PC acima. A fila Android já pertence aos Primary files e deve conservar o id do remetente entre tentativas; só o destinatário consegue confirmar um id já aceito quando o 201 se perde. A deduplicação é por id, nunca pelo conteúdo. Critérios 7 e 8 continuam reabertos para o stage 2.

- 2026-09-26 Attempt 2 failed: exit 0; blocked after two attempts. Log tail: `SYN-016` ficou `Stage: blocked` na [branch/ticket](C:/Users/bruno/Desktop/Pastinha/Programas/Projects/SynchroNice/.scratch/android/issues/07-SYN-016-android-compartilhar.md). /  / O teste HTTP comprometido permite retry automático e não prova resposta perdida. Commits: `2b6d749` e `3b0672e`; branch limpa, sem alterações de produção mantidas. /  / Próximo passo: substituir o cenário por servidor TCP de conexão única e voltar para `to-implement`.
