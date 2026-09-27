# CLEAN-013: Escuta e presença do Android
Stage: to-implement
Status: ready-for-agent
Blocked by: none
Review: agent

- Primary files:
  - android/app/src/main/java/synchronice/android/MainActivity.kt (`AppState`: laço de escuta, recibos, init; `ConnectionLifecycle`)
  - android/app/src/test/java/synchronice/android/

#### What to build

Achados na revisão do PR #7 (sessão `sweatshop/2026-09-25-2127`). São quatro
defeitos no mesmo trecho do `AppState`, por isso vêm num ticket só.

1. **Presença falsa durante o download.** A spec diz "A presença é essa
   conexão" (a escuta). O laço de escuta baixa cada arquivo recebido antes de
   reabrir o `GET /listen`, então durante um download grande o `HEAD /listen`
   do PC responde que não há escuta, e o chat mostra "Fora da rede" com o PC
   na rede. Além disso, um texto do PC fica esperando o download acabar.
2. **Pisca "Fora da rede" a cada lote.** O laço marca `online = false` logo
   depois de cada lote que a escuta devolve. Quem decide a presença é o
   `HEAD /listen`; o laço não deve mexer nela ao receber Itens.
3. **Um recibo que falha para a escuta.** Antes de cada `listen()`, o laço
   repete a confirmação dos arquivos já gravados e relança qualquer erro. Se um
   recibo falha sempre (o usuário apagou o arquivo antes do ACK), o app não
   escuta mais nada até desparear. Recibo que falha não pode parar a escuta:
   fica para a próxima tentativa. Recibo cujo arquivo não existe mais é
   descartado, sem ACK, e o Item volta a ser baixado quando o PC o devolver.
4. **Trabalho pesado na main thread ao iniciar.** O `AppState` nasce no
   `Application.onCreate` e ali decodifica a prévia de cada Pendente de imagem
   e lê a fila do disco, inclusive quando o processo acorda só para o
   WorkManager ou para o Compartilhar. Com muitas fotos Pendentes isso é ANR. A
   prévia passa a ser decodificada fora da main thread e aparece na bolha
   quando fica pronta; a leitura da fila no `onCreate` também sai da main.

#### Acceptance criteria

1. Durante o download de um arquivo recebido, a escuta continua aberta: o
   `HEAD /listen` do PC responde que há escuta, e um texto mandado pelo PC no
   meio do download aparece no chat antes de o download terminar.
2. Receber um lote de Itens não muda a presença mostrada.
3. Com um recibo cuja confirmação falha, textos novos do PC continuam chegando;
   o recibo é tentado de novo depois.
4. Um recibo cujo arquivo não existe mais é descartado, e o Item é baixado de
   novo quando o PC o devolve na escuta.
5. Criar o `AppState` com Pendentes de imagem não decodifica imagens nem lê a
   fila na main thread; a prévia aparece na bolha depois.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Tests stage 2 writes (own commit, red)

Robolectric contra um PC falso (o mesmo estilo dos testes de recebimento
existentes):
- arquivo grande servido devagar + texto enfileirado no meio → o texto aparece
  antes do fim do download, e a escuta é reaberta durante ele (critério 1);
- presença verdadeira, lote recebido → presença continua verdadeira (critério 2);
- recibo com `publish` falhando + texto novo no PC → texto chega (critério 3);
- recibo apontando para URI apagada → recibo some, novo download do Item
  (critério 4);
- `AppState` criado com um Pendente de imagem → `preview` nulo logo após a
  construção, preenchido depois de drenar o executor de fundo (critério 5).

## Comments

- 2026-09-27 Criado a partir da revisão do PR #7 (Spec (c) e Standards, bug 3).
  O stage 1 foi feito na sessão interativa.

- 2026-09-27 Attempt 1 failed: exit 0. Log tail: Waiting for the gate run to finish — will resume automatically when it completes or at the next scheduled check.
