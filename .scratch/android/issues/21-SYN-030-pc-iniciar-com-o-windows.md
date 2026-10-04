# SYN-030: Opção "Iniciar com o Windows" na engrenagem do PC
Stage: done
Status: ready-for-agent
Blocked by: none
Review: human
Difficulty: normal
Origin: triagem de CLEAN-018..025, 2026-10-03, pedido do humano

- Primary files:
  - pc/src-tauri/src/lib.rs (tirar o registro do `setup`; comandos `autostart_ligado` e `definir_autostart`)
  - pc/src-tauri/src/config.rs (quando o autostart pode ser lido e mudado)
  - pc/src-tauri/tests/config.rs
  - pc/index.html (checkbox no painel da engrenagem)
  - pc/ui/main.ts

#### What to build

Hoje o PC chama `autolaunch().enable()` em todo start fora do perfil isolado,
e o Windows sempre sobe o app no login. Isso passa a ser uma opção do usuário,
desligada por padrão (história 49 do spec).

- **Fonte da verdade.** O próprio HKCU Run, por meio do
  `tauri_plugin_autostart`. Nenhum campo novo no `config.json` nem em
  preferências. O checkbox mostra `is_enabled()`, e mudar o checkbox chama
  `enable()` ou `disable()`.
- **Nada no start.** O `setup` deixa de mexer no autostart. Numa instalação
  nova, o app não aparece no Run até o usuário marcar a opção. Numa instalação
  que já está no Run, nada muda: o checkbox aparece marcado.
- **Comandos.** `autostart_ligado` devolve o estado, e
  `definir_autostart(ligado)` muda. O plugin não ganha permissão na
  capability: a UI só fala com o autostart por esses dois comandos.
- **Perfil isolado** (`SYNCHRONICE_DATA_DIR` definido). Os dois comandos
  recusam com erro, sem ler nem gravar o Run, e o checkbox fica desabilitado.
  A recusa mora no Rust porque o kit chama comandos pelo CDP, e isso mantém a
  história 7 da spec do QA. O flag que o `resolve_data_dir` já devolve passa
  a querer dizer "pode mexer no autostart".
- **Build de debug.** `definir_autostart(true)` recusa com erro, para o Run
  nunca apontar para um exe de debug. Desligar e ler continuam valendo.
- **UI.** Um checkbox "Iniciar com o Windows", logo abaixo de "Copiar com
  pontuação", com `id="start-with-windows"`. Se a mudança falhar, o check
  volta ao estado anterior e o erro vai para o `console.error`, como em
  `mudar_pasta` e no Desparear.
- O comentário do `setup` sobre o registro idempotente sai junto com o código.

#### Acceptance criteria

1. O start do app, em release e fora do perfil isolado, não grava nem apaga
   a entrada do Run.
2. O painel da engrenagem tem o checkbox "Iniciar com o Windows" logo abaixo
   de "Copiar com pontuação", marcado se e só se o app está no Run.
3. Marcar o checkbox registra o exe em execução no Run, e desmarcar remove a
   entrada. O estado vale no próximo login e na próxima abertura do painel.
4. Se `definir_autostart` falhar, o checkbox volta ao estado anterior e o erro
   aparece no `console.error`.
5. No perfil isolado, `autostart_ligado` e `definir_autostart` devolvem erro
   sem tocar no Run, e o checkbox fica desabilitado.
6. Em build de debug, `definir_autostart(true)` devolve erro sem tocar no
   Run.
7. Uma função pura do `config.rs` diz se o autostart pode ser lido e mudado,
   a partir de "perfil isolado". Ela tem teste em `tests/config.rs`, no lugar
   do teste atual `diretorio_isolado_desliga_autostart_e_padrao_o_mantem`, que
   muda de nome se o sentido mudar.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Kit, no emulador:
- `pc.ps1 invoke -Isolated -Command autostart_ligado` devolve erro;
- o `#start-with-windows` está desabilitado no perfil isolado (critério 5).

Os critérios 1 a 4 e 6 mexem no HKCU Run real. O kit não os alcança sem
tocar no autostart do humano, então ficam como `Débito humano:`.

## Tests stage 2 writes (own commit, red)

Em `pc/src-tauri/tests/config.rs`: a função pura do critério 7. Com
`SYNCHRONICE_DATA_DIR`, o autostart não pode ser lido nem mudado; sem ela,
pode. O resto, que é o plugin, o registro e a UI, fica fora de teste
automático, como a spec já define para autostart e engrenagem.

## Comments

- Stage 2 (2026-10-03): seam confirmada pelo ticket: política pura em
  `config.rs`, exercitada por `tests/config.rs`. Chamadores examinados:
  `run`, `start_server` e `mudar_pasta`; os dois últimos usam só o caminho
  retornado por `resolve_data_dir`. O teste conserva esses caminhos e cobre
  perfil padrão, isolado e override definido mas vazio. Red: o gate
  `-TestsOnly` acusa E0425 porque `config::autostart_permitido` ainda não existe.

- Stage 2 concluído (2026-10-03): teste vermelho no commit `7f9eb57`;
  implementação em `12c6e0c`, formatação em `58beb5a`. O `setup` não registra
  autostart; os comandos conferem a permissão do perfil antes do plugin e
  recusam habilitar em debug. O checkbox consulta o plugin ao abrir o painel,
  desabilita durante operações e restaura o valor anterior se a mudança falhar.
  Nenhuma capability nem preferência persistente foi adicionada.
  Gate completo passou com `RUST_TEST_THREADS=1`: rustfmt, Clippy, TypeScript,
  83 testes Rust, 61 da UI, 14 do QA e `gradle check` (166 testes Android nos
  relatórios, zero falhas/erros; tarefas de teste em cache na rodada final).
  Tentativas anteriores: rede do Gradle negada no sandbox; ajuste de rustfmt;
  timeout PowerShell no teste QA de wifi-off; timeout de 5 s no teste Rust
  `dois_envios_concorrentes_do_mesmo_nome_preservam_ambos`. Ambos passaram
  depois, sem mudar testes; a execução sequencial dos casos Rust conserva os
  uploads concorrentes dentro do teste. Logs locais em
  `.scratch/qa-runs/SYN-030-stage2/`. Diff conferido: apenas Primary files e
  este ticket, com testes separados dos commits de implementação.
  Pendente para stage 3: kit no perfil isolado (critério 5) e verificações
  manuais dos critérios 1–4 e 6, conforme a seção Verification; o autostart
  real da máquina não foi exercitado nesta implementação.

- Triagem 2026-10-03 (/grill-proxy, com o humano):
  > *This was generated by AI during triage.*
  Pedido do humano durante a triagem dos CLEAN do kit. As decisões:
  - o Run é a fonte da verdade (Q11, humano);
  - a instalação existente fica como está (Q12, proxy);
  - o isolado recusa no Rust (Q13, proxy);
  - os docs mudam sem ADR (Q14, proxy);
  - a UI é um checkbox com o erro no `console.error` (Q15, proxy);
  - a guarda de debug e a fatia separada do CLEAN-024 (Q10, humano).

  `Review: human` porque o ticket mexe no autostart da máquina do humano.

- Stage 3 (2026-10-03): eixos Standards e Spec concluídos. Nenhuma falha de
  critério ou regressão no código; separação test-first conferida nos quatro
  commits. Correção pequena de documentação: os roteiros de
  `docs/pr-3-review-follow-up.md` e do walkthrough de 2026-09-27 deixam de
  dizer que abrir o app registra autostart; as specs core e QA descrevem a
  opção e a permissão de acesso atuais. Nenhum código ou teste foi alterado.
  Validação pelo kit e gate ainda em curso.

- QA stage 3 (2026-10-03): critério 5 passou no kit: os comandos de leitura,
  habilitação e desabilitação recusaram no perfil isolado, e o seletor CSS
  `#start-with-windows:disabled + span` confirmou o checkbox desabilitado.
  Texto e posição do critério 2 também conferidos no DOM e na captura.
  Relatório: `.scratch/qa-runs/2026-10-03-syn-030-review.md`. O SDK foi usado
  pelo destino real de sua junction, sem mudar código; `down` confirmou
  estado inativo e encerramento do emulador e do PC debug. O wrapper de saída
  do `up` precisou ser encerrado após emitir seu resultado final.

- Débito humano: SYN-030 critérios 1–4 e 6 — verificar start release sem mudar o Run, estado real do checkbox, habilitar/desabilitar e efeito no próximo login/reabertura, reversão e console.error em falha real, e recusa de habilitação em debug fora do perfil isolado. Texto/posição do critério 2 e isolamento completo do critério 5 já verificados pelo kit; APIs nativas de Computer Use indisponíveis nesta sessão.

#### Resolution (2026-10-03)
Verdict: Approve

Revisão de `e73aafc` até `ec7fb79`, em dois eixos independentes. Implementação
aprovada e integrada sem squash na sessão `sweatshop/2026-10-03-1608` pelo
merge `2125fb9`. O autostart passa a ser uma opção da engrenagem; abrir o app
preserva o estado existente do Windows.

##### Standards

- Um achado documental, corrigido em `2c6b669`: os roteiros ativos ainda
  afirmavam que abrir o app registrava autostart. Atualizados
  `docs/pr-3-review-follow-up.md`, o walkthrough de 2026-09-27 e as specs core
  e QA para descrever a opção e a permissão do perfil. A correção recebeu
  nova revisão textual/TeX; nenhum achado pendente ou smell relevante.
- Separação test-first correta: `7f9eb57` altera somente o teste e o ticket;
  `12c6e0c` implementa sem tocar testes, e `58beb5a` apenas formata
  `config.rs`. Os commits seguintes contêm documentação e evidência QA.
  Todos os arquivos de código pertencem aos Primary files. As correções de
  docs seguem a exceção explícita do ticket-flow para documentação obsoleta.
- Glossário, ADRs, capability e configuração persistente coerentes com o
  contrato. Nomes e funções permanecem simples e restritos à opção.

##### Spec

Nenhum requisito ausente, escopo extra, falha de critério ou regressão
identificado. Os critérios abaixo foram aprovados pelo fonte; o kit e o
gate complementam essa revisão. As execuções reais dos critérios 1–4 e 6
continuam no débito humano expressamente previsto em Verification.

| Critério | Veredito | Evidência |
| --- | --- | --- |
| 1 | ✅ | `setup` não chama enable/disable. O setup do plugin instalado configura o gerenciador sem ler ou gravar o registro. |
| 2 | ✅ | Checkbox com id e texto corretos, abaixo de "Copiar com pontuação", conferido no DOM e na captura. Cada abertura consulta `is_enabled()` pelo comando Rust. Estado real permanece manual. |
| 3 | ✅ | `definir_autostart` delega a enable/disable do plugin, cujo enable usa o exe atual. O painel consulta novamente na próxima abertura; registro e login reais permanecem manuais. |
| 4 | ✅ | Falha restaura o valor anterior (`!ligado`) e chama `console.error`; o controle fica bloqueado durante a operação. Falha real de escrita permanece manual. |
| 5 | ✅ | A permissão do perfil é conferida antes do plugin. No kit, leitura e as duas mudanças devolveram a recusa esperada, e o checkbox permaneceu desabilitado. |
| 6 | ✅ | `ligado && cfg!(debug_assertions)` devolve erro antes do plugin. Execução em debug fora do perfil isolado permanece manual. |
| 7 | ✅ | Função pura exercitada por `tests/config.rs`, com perfil padrão, isolado e override definido mas vazio; os caminhos dos chamadores foram preservados. |

Nota para leitura do critério 2: o `is_enabled()` exigido pelo contrato, na
dependência existente `auto-launch 0.5.0`, também considera
`StartupApproved\Run`. Uma entrada desativada pelo Gerenciador de Tarefas
aparece desmarcada; `enable()` restaura a aprovação quando essa chave é
acessível. A implementação segue os métodos de plugin definidos pelo ticket.

##### Escopo examinado

- Chamadores de `resolve_data_dir`: `run`, `start_server` e `mudar_pasta`.
  Os dois últimos continuam consumindo só o caminho. Também examinados:
  estado e registro dos comandos Tauri, capability, fonte do plugin e seu
  builder, configuração persistente e inicialização da janela.
- Caminhos de falha e interação: leitura recusada mantém o controle
  desabilitado; mudança recusada restaura o check; abrir/fechar o painel
  durante uma operação preserva o bloqueio. Pasta, Desparear, Copiar com
  pontuação, tela de QR e chat mantêm seus fluxos. O kit confirmou pareamento
  e presença com o emulador antes de verificar as configurações.
- Não exercitados: registro real, login do Windows, falhas reais de acesso
  ao registro, debug não isolado e mudanças externas simultâneas do estado
  de inicialização. O TeX foi revisado textualmente, sem gerar um novo PDF.
- Decisões herdadas da triagem: Q12 (proxy, preservar instalação existente),
  Q13 (proxy, guarda Rust do isolado), Q14 (proxy, atualização dos docs) e
  Q15 (proxy, checkbox e erro no console) estão refletidas no resultado.

##### Validação e integração

- Red-green conferido: `7f9eb57` chama a função de produção ausente e o log
  red registra E0425 para `config::autostart_permitido`; `12c6e0c` fornece a
  função. O gate desta revisão aprovou o mesmo teste, sem alterar testes.
- Rebase na sessão já atualizado, sem conflito. Gate completo executado em
  primeiro plano com `RUST_TEST_THREADS=1`: fmt, Clippy, TypeScript,
  83 testes Rust, 61 da UI e 14 do QA passaram. `gradle check` passou;
  relatórios XML têm 94 testes do app e 72 do protocolo, zero falhas, erros
  ou ignorados. As tarefas de teste Android estavam `UP-TO-DATE`.
  Log: `.scratch/qa-runs/2026-10-03-syn-030-review/gate.log`.
- QA: `.scratch/qa-runs/2026-10-03-syn-030-review.md`, com critério 5 completo
  e texto/posição do critério 2. O relatório registra o caminho real do SDK,
  a limitação do wrapper de saída e a limpeza confirmada por `down`.
- O build Tauri regravou `Cargo.toml` sem diff de conteúdo; restaurada essa
  regravação antes do rebase. Diff final conferido: código do ticket,
  correção documental, relatório, ticket e ledger; nenhum segredo, log ou
  captura adicionado ao git. A árvore estava limpa antes da integração.
- `Stage: done`, esta Resolution e a linha do ledger registrados juntos na
  branch de sessão. O PR da sessão segue com o driver e o merge com o humano.

Totais da revisão: Standards — 1 achado documental resolvido, 0 pendentes;
Spec — 0 falhas, com a nota de comportamento da dependência registrada acima.
