# BUG-003: O PC perde os enviados ao recarregar, e a bolha enviada não abre nem arrasta
Stage: done
Status: ready-for-agent
Blocked by: none
Review: agent
Difficulty: normal

- Primary files:
  - pc/src-tauri/src/outbox.rs (lista dos enviados da Sessão e arquivo de cada um)
  - pc/src-tauri/src/lib.rs (`send_text`, `send_files`, `send_bytes`; comandos de listar, abrir, arrastar e ler bytes da enviada)
  - pc/ui/main.ts (carga inicial, `renderEnviado`, `renderArquivoEnviado`)
  - pc/ui/styles.css (cursor e miniatura da bolha enviada)
  - New: pc/src-tauri/tests/outbox.rs

#### What to build

Achado do walkthrough de QA (2026-09-30). Dois sintomas, uma causa: o backend
não lembra o que o PC mandou.

1. Recarregar a janela apaga as bolhas enviadas pelo PC; as recebidas voltam.
   O `main.ts` só relista os recebidos (`list_items`), e a bolha enviada só
   nasce do evento `enviado`. A Sessão é a vida do processo (ADR-0002), e
   recarregar não a encerra.
2. A bolha de arquivo enviado pelo PC não tem miniatura, não abre com clique e
   não arrasta. A recebida faz as três coisas desde a3f6618. Depois do ack,
   `Outbox::confirm` apaga a cópia, e o PC só guarda o nome do arquivo.

Decisões do stage 1 (2026-10-01):

- **Lista dos enviados.** O backend guarda, em memória, os enviados desta vida
  do processo, Pendentes e Entregues, com o arquivo de cada um. A UI relista os
  dois lados ao carregar, em ordem de `at_ms` (no empate, a ordem de inserção).
  Pendentes herdados de um processo anterior continuam fora do chat, como hoje.
  O recarregar continua ligado.
- **O arquivo da enviada é o caminho original**, o do drop ou da lista do
  Explorer. Nada é copiado a mais.
- **O print colado não tem original.** Ganha uma cópia própria, com o nome do
  Item, na pasta de dados do app. A cópia vive até a Sessão acabar, e a pasta
  é varrida quando a fila é criada, porque o processo pode morrer sem limpar.
- **A bolha enviada de arquivo se comporta como a recebida:** miniatura se for
  imagem, clique abre (com o guard de executável), arrastar leva o arquivo e
  liga `arrastandoDaBolha`. Vale desde Pendente. O arquivo aberto ou arrastado
  tem o nome do Item, nunca `<id>.bin`.
- **Original que sumiu** (movido ou apagado): a bolha fica. Cada tentativa
  consulta de novo e marca ou desmarca `sumiu`, reusando
  `marcarArquivoSumido`/`desmarcarArquivoSumido`. A legenda não fala em "Pasta"
  quando o arquivo não é da Pasta.
- Os ids de `Items` e de `OutItem` são sequências diferentes e colidem: os
  comandos da enviada têm chave própria.

#### Acceptance criteria

1. Um arquivo enfileirado com caminho de origem aparece na lista de enviados da
   Sessão, Pendente e com esse caminho; depois do ack continua listado,
   Entregue e com o mesmo caminho.
2. Um texto enfileirado aparece na lista, Pendente, e depois do ack, Entregue.
3. Bytes enfileirados sem caminho (o print colado) geram uma cópia com o nome
   do Item, que continua existindo depois do ack.
4. Uma cópia de print deixada por um processo anterior é apagada quando a fila
   é criada.
5. Pendentes carregados do manifesto de um processo anterior não entram na
   lista de enviados da Sessão.
6. Recarregar a janela mostra as enviadas e as recebidas em ordem de `at_ms`,
   cada enviada com seu estado (Pendente ou Entregue).
7. A bolha de imagem enviada mostra miniatura; clicar numa enviada de arquivo
   abre o arquivo; arrastar leva o arquivo com o nome do Item.
8. Com o original apagado depois do envio, clicar na bolha a marca como sumida,
   sem erro; com o arquivo de volta no lugar, o próximo clique abre.
9. Os testes existentes seguem verdes.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

QA no stage 3: kit no emulador, critérios 6 a 8 (o recarregar e o DOM pelo
CDP). Arrastar para fora da janela continua 👤.

Depois da reabertura de 2026-10-02, o QA do stage 3 repete os dois cenários
que falharam (relatório `.scratch/qa-runs/2026-10-02-bug-003-final-review.md`):
duas instâncias com a mesma pasta de dados, e a abertura com a fila quebrada
(`data/outbox` como arquivo), em que a engrenagem precisa responder.

## Tests stage 2 writes (own commit, red)

- `pc/src-tauri/tests/outbox.rs` (novo), na API pública de `pc_lib::outbox`,
  critérios 1 a 5. Vermelho porque a fila não guarda a origem, não lista os
  confirmados, e `confirm` apaga a única cópia.
- Reabertura (2026-10-02), no mesmo `pc/src-tauri/tests/outbox.rs`: duas
  filas vivas na mesma pasta. Criar a segunda não apaga a cópia de print da
  primeira, que segue legível depois do ack; descartar uma apaga só as
  cópias dela. Vermelho porque `Outbox::new` e o `Drop` apagam a pasta
  `session-files` inteira. O teste atual do critério 4 simula a morte com
  `std::mem::forget`, que no mesmo processo não se distingue de uma
  instância viva: troque a simulação por um processo que morre de fato (por
  exemplo, o próprio binário de teste reexecutado, saindo com
  `std::process::exit` depois de colar o print). É correção de harness, em
  commit só de teste.
- A regressão da carga inicial em `pc/ui/main.ts` não tem seam de teste:
  `main()` roda no import e o gate só executa `copiaveis.test.ts`. Corrige-se
  sem teste de unidade, como a correção 2 da revisão de SYN-005
  (`estado_par_inicial` com `catch`); a prova é o cenário de QA da fila
  quebrada em Verification.

## Comments

- Stage 2, reabertura (2026-10-02): chamadores reconferidos: criação da fila
  em `start_server` e nos testes do servidor; cópias usadas por envio de bytes,
  leitura/abertura/drag da enviada, ACK e último clone. `clear` continua
  descartando só Pendentes. Novo teste na API pública reproduziu a perda:
  `filas_vivas_preservam_prints_umas_das_outras` falha com `NotFound` ao ler
  o print confirmado depois de criar a segunda fila; `outbox` 4/5 verdes.
  O harness do critério 4 usa agora o próprio binário em processo filho,
  `process::exit` sem destrutores, com os mesmos asserts de Sessão/Pendentes;
  passou na implementação anterior. O `mem::forget` não simulava processo morto.

- Stage 2 (2026-10-01): chamadores conferidos: comandos de envio em `lib.rs`,
  download/ACK/escuta no servidor e `Par::rotate` (`clear`). Primeiro vermelho:
  `gate.ps1 -TestsOnly` sai 1, com E0599 nas APIs de origem/lista/caminho da
  Sessão. O teste cobre ACK repetido, origem apagada/restaurada e cópia de
  entrega independente da origem. UI 47/47, kit 14/14 e Android verdes.
- Primeiro verde: PC 53/53 (`outbox` 1/1), UI 47/47, kit 14/14,
  Android verde. Segundo vermelho: `outbox` 1 verde/3 vermelhos — lista
  de texto/prints vazia, cópia de print ausente e nome com caminho aceito.
  O ciclo de vida cobre também nomes repetidos, bytes vazios, limpeza após
  morte sem destrutor, Pendentes herdados sem bolha/aviso e último clone.
- Stage 2 concluído (2026-10-01), branch `bug-003`, base
  `sweatshop/2026-10-01-2019`. Testes vermelhos em `f19a2ca` e `a292e88`,
  separados dos commits de produção; formatação dos testes em `ba21bb3`.
  Gate completo: **PC 56/56** (4 novos em `outbox`), **UI 47/47**, **kit
  14/14**, fmt, Clippy e typecheck verdes, `gradle check` verde (133 testes
  Android nos relatórios, reutilizados pelo Gradle como `UP-TO-DATE`).
  A primeira rodada completa encontrou E0277 no payload de evento recebido;
  o derive `Clone` foi corrigido antes desta rodada verde.
  Os enviados vivem em memória; arquivos usam a origem e prints usam
  `outbox/session-files/<id>/<nome>`. ACK apaga só o payload de entrega.
  A UI relista ambos os lados e desempata `at_ms` com ordem de inserção
  compartilhada; comandos de arquivo enviados têm namespace próprio e o
  guard de executável é compartilhado. Snapshot atrasado não desfaz ACK.
  QA no emulador dos critérios 6–8 e drag nativo aguardam o stage 3.

- Stage 1 (2026-10-01), grilling com o dono e o `proxy`. O dono escolheu o
  caminho original, com cópia só para o print colado, em vez de uma cópia de
  tudo em `Pasta\Enviados` ou de manter a cópia da fila até o fim da Sessão.
  O `proxy` decidiu o resto. Paridade no Android fica fora: lá a imagem enviada
  já abre, e o PDF enviado não; não há ticket.

- 2026-10-02 Review ended at to-review (timeout); branch bug-003 holds the review; left for a human

- 2026-10-02 Foreman: o review (gpt-6.1-sol max, 23:42) estourou os 40 min compilando o PC e montando o QA (pareamento do emulador falhou; abrir o Explorer pelo Computer Use estourou o tempo) e não registrou nada. Suspeitas que ele anotou antes de parar: a limpeza de prints entre instâncias e os controles da bolha interrompidos quando a fila falha. Log: `.scratch/run-log/BUG-003-review-20261001-234229.txt`. Stage de volta a `to-review` para nova tentativa.

- 2026-10-02 Review ended at to-review (timeout); branch bug-003 holds the review; left for a human

- 2026-10-02 Proxy decided: reabrir pelo veredito da segunda revisão, sem terceira tentativa — os dois achados estão reproduzidos no relatório de `1e8b32b` e conferem com o código da branch; outra revisão gastaria de novo os 40 min compilando o PC e montando o QA para chegar ao mesmo ponto.

- 2026-10-02 Proxy decided: a carga inicial se corrige sem teste de unidade, e a morte de processo no teste do critério 4 passa a ser um processo de verdade — `main.ts` não tem seam testável (precedente: SYN-005, correção 2) e `mem::forget` no mesmo processo não separa morte de instância viva (ver "Tests stage 2 writes").

#### Review stage 3 (2026-10-02)

Verdict: Reopen — critérios 3 e 7: outra instância apaga a cópia de print de uma Sessão viva; regressão: falha de `list_sent_items` deixa a janela sem controles.

Revisão gpt-6.1-sol max (`.scratch/run-log/BUG-003-review-20261002-035109.txt`).
A sessão chegou ao veredito e estourou os 40 min antes de escrevê-lo aqui; o
`proxy` transcreveu este bloco do relatório
`.scratch/qa-runs/2026-10-02-bug-003-final-review.md` (`1e8b32b`) e conferiu
os dois achados no código da branch. Base `sweatshop/2026-10-01-2019`
(merge-base `608165d`), implementação revisada até `6cb56d9`.

##### Standards

- Primary files respeitados. Commits de teste (`a53a09a`, `01ffd9e`,
  `d9972e8`) antes dos de produção (`59b6ead`, `f2d5e88`, `6cb56d9`), que
  não tocam testes.
- Sugestão, sem reprovação: a direção da bolha é lida várias vezes de
  `classList.contains("mine")` em `main.ts`. Não é critério nem regressão.

##### Spec

- ❌ **Critérios 3 e 7, cópia de print apagada por outra instância (P2)** —
  `outbox.rs`: `Outbox::new` e `Inner::drop` apagam a pasta compartilhada
  `session-files` inteira, e o PC não tem instância única (abrir o atalho com
  o app na bandeja abre uma segunda, como anotado em SYN-005 e em
  `.scratch/qa/spec.md`). Reproduzido: print id 3 colado e confirmado pelo
  `/outbox/3/ack` real na instância 1; abrir a instância 2 com a mesma pasta
  de dados apagou a cópia, `read_sent_file_bytes(3)` passou a falhar e o
  clique marcou a bolha como sumida, com as duas vivas. "A cópia vive até a
  Sessão acabar" vale por instância: a varredura do critério 4 e o destrutor
  só podem apagar cópias de processo morto ou as próprias.
- ❌ **Regressão na carga inicial (P2)** — `main.ts`: o `Promise.all` de
  `list_items` e `list_sent_items` rejeita quando a fila não foi criada
  (`lib.rs` retorna antes de `app.manage(outbox)` se `Outbox::new` falha, com
  `Items` e `Par` já gerenciados). `main()` aborta antes de desenhar os
  recebidos e de `ligarComposer`, `ligarDrop`, `ligarColar` e
  `ligarConfiguracoes`, que antes do BUG-003 eram ligados. Reproduzido com
  `data/outbox` como arquivo (`os error 183`): engrenagem sem resposta. É o
  modo de falha da correção 2 da revisão de SYN-005.
- Critérios 1, 2, 4, 5, 6 e 8 ✅ (testes de `outbox`; QA pelo kit, DOM e
  recarregar pelo CDP). Critério 7 ✅ num processo só (miniatura, abrir,
  resolução do drag); arrastar para fora da janela 👤 (captura do Computer
  Use estourou o tempo).
- Linhas `Proxy decided:` anteriores a esta revisão: nenhuma.

##### Validação e próximo passo

- Gate completo, segunda rodada: PC 56/56, UI 47/47, kit 14/14, fmt, Clippy,
  typecheck e `gradle check` verdes. A primeira rodada falhou só em
  `envios_concorrentes_do_mesmo_id_criam_um_item_e_um_arquivo`
  (`tests/server.rs:819`, Windows 10053 `ConnectionAborted` no
  `read_to_string`), teste e código que o BUG-003 não toca: instabilidade
  fora do ticket, para triagem.
- QA: o emulador abortou por infraestrutura (presença não ficou 200 em 30 s,
  não reprova); o PC rodou isolado pelo kit. Evidências e limpeza no
  relatório acima.
- Retomar o stage 2 na branch `bug-003` pelos dois ❌, com testes novos em
  commit próprio antes do código (ver "Tests stage 2 writes", reabertura).
  Sem merge nem linha de ledger.

#### Stage 2 — reabertura concluída (2026-10-02)

- Testes primeiro em `7759b1f`: a segunda fila apagava o print confirmado
  da primeira (`NotFound`, `outbox` 4/5). `gate.ps1 -TestsOnly` saiu 1
  por esse teste; UI 47/47, kit 14/14 e Android verdes. O Cargo interrompeu
  antes dos testes de servidor nessa rodada vermelha.
- Correção dentro dos Primary files: cada fila ganha seu diretório em
  `outbox/session-files/<sessão>/<id>/<nome>`. Um lock de arquivo por fila
  identifica a dona viva, inclusive entre processos; um lock da pasta
  serializa criação, varredura e descarte, impedindo varrer uma pasta ainda
  em criação. O último clone remove só as próprias cópias. Morte sem
  destrutores libera o lock, e a próxima fila remove as cópias abandonadas.
  Manifesto e payloads de entrega continuam com o ciclo de vida anterior.
- A carga inicial trata a falha de cada lista separadamente, preservando
  a outra lista e a ligação do composer, drop, colagem e configurações.
  Sem teste de unidade, conforme a decisão registrada na reabertura.
- Verde: gate completo saiu 0, fmt/Clippy/typecheck verdes, **PC 57/57**
  (4 unitários, 7 configuração, 5 outbox, 41 servidor), **UI 47/47**,
  **kit 14/14**, `gradle check` verde (133 testes nos relatórios existentes,
  sem falhas, tarefas de teste `UP-TO-DATE`). Build debug sem bundle também
  passou. Produção não altera testes.
- Smoke no executável novo, PC isolado pelo kit/CDP:
  - `data/outbox` como arquivo: `list_items` retorna `[]`,
    `list_sent_items` rejeita, mas a engrenagem abre/fecha e volta a abrir
    após reload. Screenshot local `broken.png`.
  - Print PNG de 68 bytes enfileirado por `send_bytes` e confirmado pelo
    endpoint HTTP real: duas instâncias vivas, mesma pasta de dados,
    perfis WebView/CDP distintos. Na primeira, `read_sent_file_bytes`
    conserva os 68 bytes, o caminho de drag mantém `qa-overlap.png`,
    e a miniatura volta após reload (`naturalWidth=1`). Repetido com
    sucesso após encerrar a segunda instância. Screenshot `overlap.png`.
  - Evidências/scripts/logs locais ignorados em
    `.scratch/qa-runs/2026-10-02-bug-003-reopen/`; Pasta isolada vazia,
    `clean` retorna `[]`, processos de QA encerrados. Nenhum arquivo,
    Par ou Sessão reais alterados.
- Próximo passo: stage 3 independente, incluindo QA no emulador e os passos
  de Verification. O smoke não verifica Android nem gesto de drag externo.
  Sem merge, push ou ledger nesta etapa.

#### Resolution (2026-10-02)

Verdict: Approve

- Stage 3 independente: Standards e Spec em agentes separados; nenhum
  achado novo que reprove o ticket. Re-review dos dois ❌ anteriores e do
  diff da reabertura. A sugestão anterior sobre `classList.contains("mine")`
  continua sem reprovação; nenhuma refatoração ou correção de produção nesta
  etapa. Os dois `Proxy decided:` foram conferidos: reabrir pelas falhas
  reproduzidas e permitir a correção da carga sem teste unitário, usando
  morte real de processo no harness. A implementação segue ambos.
- Arquivos de produção: `pc/src-tauri/src/outbox.rs`,
  `pc/src-tauri/src/lib.rs`, `pc/ui/main.ts`, `pc/ui/styles.css`; testes
  públicos em `pc/src-tauri/tests/outbox.rs`. Primary files respeitados.
- Prova red-green da reabertura: testes em `7759b1f` (após rebase `b2e2ed2`),
  outbox **4/5**, falha `NotFound` da cópia ao criar outra fila viva;
  produção em `7460447` (após rebase `b61edba`), outbox **5/5** no gate desta
  revisão. Produção não altera testes; harness do critério 4 usa processo
  filho que termina sem destrutores. Vermelho histórico conferido no log.
- Gate completo em primeiro plano após rebase sobre a sessão: saída 0,
  **PC 57/57**, **UI 47/47**, **kit 14/14**; fmt, Clippy e typecheck verdes.
  `gradle check` verde, 133 testes Android sem falhas/erros nos XMLs
  reutilizados como `UP-TO-DATE`. Diff final sem alterações semânticas
  geradas pelo build ou arquivos fora do escopo de produção.
- QA do executável: reload conserva seis bolhas, ordem e estados;
  miniaturas de origem e print colado, abertura real do TXT, original
  ausente/restaurado e comandos com ids colidentes passaram. Print confirmado
  continua legível com dois processos vivos na mesma pasta de dados e após
  encerrar o segundo; thumbnail e nome correto voltam no reload. Com
  `data/outbox` como arquivo, lista de enviados falha, mas engrenagem
  abre/fecha e volta a abrir após reload. Os dois motivos da reabertura
  estão resolvidos. Evidências e limpeza em
  `.scratch/qa-runs/2026-10-02-bug-003-rereview.md`.
- Infraestrutura: kit compilou e pareou o emulador (presença 200), mas a UI
  apresentou ANR do app e, após uma retentativa, do System UI. Trilha
  interativa abortada; cenários do PC verificados com kit/CDP e HTTP real,
  sem afirmar entrega pelo app Android. Computer Use tentou o Explorer;
  as duas capturas nativas falharam. Emulador e processos de QA encerrados;
  os dois recebidos artificiais foram para a Lixeira pelo kit.
- Integrado sem squash em `sweatshop/2026-10-01-2019`, merge `f7ae9fb`.
  `Stage: done` e ledger registrados juntos na sessão. Sem push ou PR
  individual; merge do PR de sessão continua no fluxo humano.
- Débito humano: verificar o gesto de arrastar uma bolha enviada para fora
  da janela; caminho/nome do arquivo verificados, mas a captura do Computer
  Use falhou em duas tentativas.
