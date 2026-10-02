# SYN-021: Colar imagem no chat do Android, numa bandeja como a do PC
Stage: done
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

- Retomada stage 2 (2026-10-01): regressões da revisão na costura do
  AppState e na integração de Voltar da MainActivity, nos Primary files.
  Callers: contentReceiver usa a colagem; câmera e Compartilhar continuam
  usando queueFile/copyToStaging diretamente. Casos de fronteira: texto
  sem URI, URI sem texto, URI recusada ou ilegível com legenda, bandeja vazia.

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

#### Review stage 3 (2026-10-01)

Verdict: Reopen — S2-implícito: perda do texto na colagem mista; critério 7: Voltar em Configurações deixa a bandeja cheia ao sair.

Base da revisão: `b3d1d2b` (`sweatshop/2026-10-01-2019`); implementação
revisada até `b2f8fd3`. Os eixos Standards e Spec foram revisados por agentes
independentes. Todos os achados desta passagem estão abaixo.

##### Standards

- Limpeza, sem reprovação: `MainActivity.kt:905` — o botão ✕ tem
  `contentDescription`, mas não tem `testTag`. `docs/agents/qa.md` exige tag
  em cada elemento tocado por um critério; o critério 7 toca esse botão.
  Cabe uma correção pequena nos Primary files, sem teste novo.
- Nenhum outro desvio documentado ou smell identificado. Primary files e
  commits separados de testes antes da implementação foram respeitados.

##### Spec

- ❌ **S2-implícito, regressão de colagem de texto (P2)** —
  `MainActivity.kt:919–929`: para `ClipData.Item("legenda", null, uri)`, o
  receiver copia a imagem e retorna `true` a `content.consume`. O Compose
  remove o item inteiro, incluindo `item.text`; assim a legenda não chega
  ao campo. No campo anterior, `TextFieldSelectionManager` lia esse texto
  com `ClipboardUtils.readAnnotatedString`, independentemente da URI.
  Evidência: inspeção com `javap` do Compose 1.12.1 instalado no projeto;
  `consume` devolve `null` quando o único item é consumido, e o caminho
  anterior lê `getItemAt(0).getText()`. A frase do contrato "Clip com texto
  e imagem: a parte de texto cai no campo" confirma a intenção, mas a
  reprovação se apoia na regressão, não numa exigência nova em prosa.
  Falta teste sobre o recebimento de um item que contenha texto e URI juntos,
  exercitando o código de produção, e preservação desse texto na colagem.
- ❌ **Critério 7, Voltar com a bandeja cheia (P2)** —
  `MainActivity.kt:802–805,862–864`: o `BackHandler` só existe dentro de
  `ChatScreen`. Abrir Configurações remove o chat da composição;
  `SettingsScreen` não instala handler de Voltar. Repro: colar uma imagem →
  abrir Configurações → usar o Voltar do Android. O sistema pode sair do app
  sem chamar `clearPasteTray`, deixando
  a bandeja e as cópias em staging. O contrato exige "o Voltar com a bandeja
  cheia limpa a bandeja antes de sair". A correção deve cobrir a saída com a
  bandeja cheia também fora do chat, com teste de regressão proporcional.
  O botão visível Voltar de Configurações já retorna ao chat normalmente.
- Nenhum outro achado de escopo. Câmera e Compartilhar mantêm envio direto;
  erros normais ao enfileirar preservam o arquivo que falhou e os restantes.

##### Validação e próximo passo

- Rebase sobre a branch de sessão: já atualizado, sem conflito.
- Gate completo passou: Rust 52, UI 47, kit QA 14, protocolo JVM 62,
  app Android 67 (8 em `PasteTrayTest`), total 242, zero falhas.
  As tarefas de teste Gradle estavam `UP-TO-DATE`; contagens conferidas nos
  XMLs do runner. fmt, Clippy, TypeScript e lint Android passaram.
- Prova red-green e separação dos commits verificadas: `c152121` antes de
  `6b9122e`; `8ce2234` antes de `b2f8fd3`. Os logs vermelhos mostram ausência
  das APIs de produção; os commits de produção não alteraram testes.
- QA do critério 7 abortado por infraestrutura: `qa/up.ps1` retornou
  "emulador nao abriu (WMI 9)"; o SDK não contém `emulator/emulator.exe`,
  o adb não lista aparelhos e o Computer Use não encontrou janela Android.
  Relatório: `.scratch/qa-runs/2026-10-01-syn-021-review.md`.
  Os dois achados acima foram verificados por inspeção de código/bytecode;
  não houve reprodução visual nesta passagem. A falha de infraestrutura
  não é motivo da reprovação. `qa/down.ps1` confirmado, sem arquivos de
  usuário criados ou apagados.
- Retomar stage 2 no branch `syn-021` pelos dois ❌, com novos testes em
  commits próprios antes do código. Nenhuma alteração de critério ou seam
  foi feita na revisão. Sem merge nem entrada no ledger.

#### Retomada stage 2 (2026-10-01)

- Corrigidos os dois ❌ da revisão. `contentReceiver` delega a colagem ao
  AppState: copia a URI imediatamente e deixa o item com texto explícito
  para o campo inserir a legenda. Texto sozinho, URI sozinha e legendas
  com URI recusada ou ilegível têm cobertura. Câmera e Compartilhar não
  foram alterados.
- O BackHandler passou de ChatScreen para a raiz da MainActivity, mantendo
  a proteção durante envio. O teste abre Configurações pelo botão real da
  árvore semântica Compose, verifica que o primeiro Voltar apaga a cópia e
  esvazia a bandeja sem encerrar a atividade, e que o segundo permite sair.
- Limpeza indicada em Standards: cada botão ✕ recebeu uma testTag distinta
  (`remove-paste-<id do staging>`), preservando a descrição acessível.
- Prova red-green: `4294ac5` contém só testes e metadados do ticket; o gate
  `-TestsOnly` falhou por ausência de pasteItem. `bbc892c` contém só código
  de produção; o gate de testes passou com 11 casos em PasteTrayTest.
  `387c7b9` adicionou só o teste de Voltar: o gate mostrou 71 testes Android,
  um vermelho na asserção de bandeja vazia após Voltar em Configurações.
  Com a correção deste commit, os 12 casos de PasteTrayTest passaram.
- Gate completo passou: Rust 52, UI 47, kit QA 14, protocolo JVM 62 e app
  Android 71, total 246, zero falhas. fmt, Clippy, TypeScript e lint Android
  passaram. Contagens JVM conferidas nos XMLs; nenhum teste ignorado.
  Logs locais: `.scratch/run-log/syn-021-retry-paste-red.log`,
  `syn-021-retry-paste-green.log`, `syn-021-retry-back-red.log` e
  `syn-021-retry-gate.log`, todos no mesmo diretório.
- Diff revisado e `git diff --check` limpo; commits de produção não alteram
  testes. QA visual do critério 7 permanece para o stage 3; esta retomada
  verificou a navegação em Robolectric, sem execução em aparelho/emulador.

#### Resolution (2026-10-01)

Verdict: Approve

Revisão da retomada `9ce3c44...24fda0e`, contra a base de sessão `b3d1d2b`.
Standards e Spec foram revisados por agentes independentes. Nenhuma mudança
de produção ou de testes foi feita nesta revisão.

##### Standards

- Zero achados pendentes. Cada botão ✕ agora tem `testTag` baseada no UUID
  do staging e preserva a descrição acessível, conforme `docs/agents/qa.md`.
- Primary files e separação test-first respeitados: `4294ac5` e `387c7b9`
  adicionam testes antes das correções; `bbc892c` e `24fda0e` não alteram
  testes. Os testes exercitam o AppState e a navegação Compose de produção.
- Nenhum desvio documentado ou smell acionável no diff da retomada.

##### Spec

- Corrigida a regressão de colagem mista: `pasteItem` copia a URI dentro do
  callback e retorna `false` quando o item contém texto explícito. O
  bytecode do Compose 1.12.1 instalado confirma que o campo insere o texto
  dos itens restantes. As legendas também sobrevivem à URI recusada ou
  ilegível; texto sozinho e URI sozinha têm cobertura.
- Corrigido o critério 7: o BackHandler na raiz da MainActivity continua
  ativo em Configurações. O teste abre essa tela pela ação Compose real,
  verifica exclusão da cópia e bandeja vazia sem encerrar a Activity no
  primeiro Voltar, e saída no segundo. O envio em andamento segue protegido.
- Critérios 1–6 preservados, câmera e Compartilhar continuam enviando
  diretamente. Nenhum requisito faltante, escopo extra ou regressão
  identificado no diff desta passagem. Spec: zero achados pendentes.

##### Validação e integração

- Prova red-green conferida nos commits e logs: `4294ac5` falha por ausência
  de `pasteItem`, seguido de `bbc892c` verde; `387c7b9` falha na asserção de
  bandeja vazia após Voltar em Configurações, seguido de `24fda0e` verde.
  A prova original permanece em `c152121`/`6b9122e` e `8ce2234`/`b2f8fd3`.
- Rebase sobre `sweatshop/2026-10-01-2019`: já atualizado, sem conflito.
  Gate completo passou: Rust 52, UI 47, kit QA 14, protocolo JVM 62 e app
  Android 71 (12 em PasteTrayTest), total 246, zero falhas ou ignorados.
  fmt, Clippy, TypeScript e lint Android passaram. As tarefas JVM estavam
  `UP-TO-DATE`; as contagens foram conferidas nos XMLs do runner.
  Log local: `.scratch/run-log/syn-021-rereview-gate.log`.
- QA do critério 7 abortou por infraestrutura: SDK sem
  `emulator/emulator.exe`, `qa/up.ps1` retornou WMI 9 e adb não listou
  aparelhos. Computer Use inicializou e não encontrou janela Android.
  Relatório: `.scratch/qa-runs/2026-10-01-syn-021-rereview.md`.
  Isso não reprova o ticket, conforme a regra local de QA.
- `qa/down.ps1` confirmado com o mesmo RunDir e SDK: `stopped=true`,
  `restored=false`, estado inativo. Nenhum arquivo de usuário criado ou
  apagado. `git diff --check` limpo; diff final restrito ao ticket, ledger
  e relatório QA.
- Integrado sem squash na branch de sessão pelo merge `b1700f5`;
  implementação final `24fda0e`, registrada no ledger. O PR da sessão
  continua sendo responsabilidade do driver e seu merge, do humano.

- Débito humano: verificar no aparelho o critério 7 do SYN-021: colar imagem pelo teclado/menu e ver a bandeja acima do campo, remover pelo ✕, usar Voltar com a bandeja cheia antes de sair e confirmar que a câmera continua enviando diretamente. Kit e Computer Use tentados; não havia emulador, aparelho conectado ou janela Android disponível.
