# SYN-021: Colar imagem no chat do Android, numa bandeja como a do PC
Stage: to-review
Status: ready-for-agent
Blocked by: none
Review: agent
Difficulty: normal

- Primary files:
  - android/app/src/main/java/synchronice/android/MainActivity.kt (composer com `TextFieldState` e `contentReceiver`, bandeja no `AppState`, Voltar)
  - android/app/src/main/java/synchronice/android/ShareActivity.kt (só para reusar `copyToStaging` e `isForeignContentUri`)
  - New: android/app/src/test/java/synchronice/android/PasteTrayTest.kt

#### What to build

Pedido no walkthrough de QA (2026-09-30): colar uma imagem no composer do
celular, como no PC. O teclado (Gboard) e o menu de colar entregam conteúdo
rico; no Compose, isso chega pelo `Modifier.contentReceiver` num campo de
`TextFieldState` (foundation 1.12 e material3 1.4, do BOM 2026.09). O campo
atual, `OutlinedTextField(value: String)`, troca para a versão de
`TextFieldState`.

Decisões do stage 1 (2026-10-01):

- **Bandeja, como no PC (SYN-020).** O que é colado fica numa bandeja acima do
  campo, com um ✕ em cada entrada, e só o Enviar manda.
- **O que entra:** imagem (inclusive GIF e figurinha do Gboard) e qualquer
  arquivo que o `contentReceiver` entregar, uma entrada por item do
  `ClipData`, nunca agrupadas.
  - URIs do próprio app são recusadas, com o filtro `isForeignContentUri` do
    CLEAN-012.
  - A cópia para o staging é feita dentro do callback, porque a permissão da
    URI do teclado é temporária.
  - O nome é o `DISPLAY_NAME`, como no Compartilhar. Sem ele, o nome é
    `imagem-<ts>.<ext do mime>`, nunca sem extensão.
- **Clip com texto e imagem:** a parte de texto cai no campo, que é o que sobra
  do `TransferableContent`.
- **Enviar.** O botão fica habilitado com a bandeja cheia, mesmo com o campo
  vazio. Manda as entradas na ordem em que entraram, com o texto por último, e
  esvazia a bandeja e o campo.
- **Voltar** com a bandeja cheia limpa a bandeja primeiro, e só o próximo
  Voltar sai do app.
- **Vida da bandeja:** fica no `AppState`, em memória, e morre com o processo.
  Remover ou limpar apaga as cópias do staging.
- **Ficam como estão:** a câmera continua mandando direto (história 24 da spec
  Android), e o Compartilhar também. "Guardar fotos tiradas" não vale para
  imagem colada.

#### Acceptance criteria

1. Colar duas URIs de conteúdo põe duas entradas na bandeja, já copiadas para o
   staging, e nada entra na fila de Pendentes.
2. Enviar com a bandeja e texto enfileira os arquivos na ordem de entrada e o
   texto por último; a bandeja e o campo esvaziam.
3. Enviar com a bandeja cheia e o campo vazio enfileira só a bandeja.
4. Remover uma entrada apaga a cópia dela no staging; limpar apaga todas.
5. Uma URI do próprio app é recusada e não entra na bandeja.
6. Sem `DISPLAY_NAME`, o nome é `imagem-<ts>.<ext>`, com a extensão do mime.
7. No aparelho:
   - colar uma imagem pelo teclado ou pelo menu põe a entrada na bandeja,
     acima do campo;
   - o ✕ tira a entrada;
   - o Voltar com a bandeja cheia limpa a bandeja antes de sair;
   - a câmera continua mandando direto.
8. Os testes existentes seguem verdes.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

QA no stage 3: o critério 7 no emulador, até onde o kit alcança. Colar imagem
pelo Gboard provavelmente fica 👤.

## Tests stage 2 writes (own commit, red)

- `PasteTrayTest.kt` (novo, Robolectric, como o `ShareActivityTest`), sobre a
  bandeja do `AppState`, critérios 1 a 6. Vermelho porque a bandeja não
  existe.

## Comments

- Stage 2 (2026-10-01): primeiro teste vermelho pelo gate `-TestsOnly`:
  `PasteTrayTest` não compila porque `pasteUri` e `pasteTray` ainda não existem.
  Costura: bandeja do AppState. Callers preservados: ChatScreen envia texto;
  câmera e ShareModel usam queueFile; ShareModel usa copyToStaging e o filtro
  isForeignContentUri. Os testes seguintes cobrem envio vazio, remoção,
  URI própria, nome ausente e falha de leitura.

- Stage 1 (2026-10-01), grilling com o dono e o `proxy`. O dono decidiu pela
  bandeja como a do PC, em vez de enviar direto como a câmera; o `proxy`
  decidiu o resto.
- Deixou de ser `Blocked by: SYN-020`: a dependência era a decisão de
  comportamento, não código. Agora as duas bandejas se comportam igual.

#### Stage 2 (2026-10-01)

- Implementado no branch `syn-021`, base `sweatshop/2026-10-01-2019`.
  Bandeja em memória no AppState, cópia síncrona no recebimento, nomes por
  DISPLAY_NAME ou MIME, filtro de URI própria, remoção e limpeza do staging.
  O composer usa TextFieldState e contentReceiver; itens de texto restantes
  seguem para o campo. Enviar enfileira os arquivos em ordem e o texto por
  último. A bandeja tem remoção individual e Voltar limpa antes de sair.
- Prova red-green: `c152121` adicionou o primeiro teste, vermelho por ausência
  de pasteUri/pasteTray; `6b9122e` implementou a cópia e o gate de testes passou.
  `8ce2234` adicionou os casos de envio/limpeza/segurança/nome, vermelho por
  ausência de composer/sendComposer/removePaste/clearPasteTray. Nenhum commit
  de produção alterou testes.
- Gate completo: `powershell -ExecutionPolicy Bypass -File .\gate.ps1` passou.
  Rust: 52; UI: 47; kit QA: 14; protocolo JVM: 62; app Android: 67 testes,
  incluindo 8 em PasteTrayTest. Zero falhas. fmt, Clippy, TypeScript e lint
  Android passaram. Logs locais em `.scratch/run-log/syn-021-*.log`.
- Revisão de escopo e `git diff --check` concluídas. Câmera e Compartilhar
  mantêm seus fluxos diretos; o fallback de nome específico da colagem é
  opcional no helper compartilhado. Critério 7 no aparelho permanece para o
  stage 3, conforme Verification; não foi executado neste stage.
