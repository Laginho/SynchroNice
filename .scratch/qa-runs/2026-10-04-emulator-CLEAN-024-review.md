# QA — 2026-10-04 — Emulador — CLEAN-024

Revisão da branch `clean-024`, implementação `96ffc9e`, contra
`6593287c2dbf7a4a4c936d52828351ddeb9773e5`, base da sessão
`sweatshop/2026-10-03-1608`. Rebase atualizado, sem alteração de código.
Contrato: [CLEAN-024](../qa/issues/12-CLEAN-024-pc-real-volta-ao-normal.md).

## Standards

Nenhuma violação de escopo ou separação test-first. `7269ebf` contém testes
e o ticket; `886b7e0` altera somente produção/documentação; `96ffc9e`
registra o handoff. A extração de `Start-NormalPc` reutiliza a rotina WMI
exigida pelo contrato, sem introduzir outro mecanismo.

Um julgamento de nomenclatura: `noOp`, em `qa/down.test.mjs:71`, significa
preservar o debug quando não há teardown do emulador. Nos novos cenários
esse valor pode ser verdadeiro enquanto o PC real é relançado. É um
possível Mysterious Name; a orientação de nomes claros do Engineering
Workflow favoreceria `leaveDebugRunning`. Não afeta os testes nem exige
correção para aprovar o contrato.

Quatro notas processuais do stage 2:

- Os três commits não têm corpo explicando o motivo, pedido pela seção
  "Commits and closing" de `ticket-flow`; o ID está no assunto.
- `to-review` foi commitado no handoff, separado do último commit de código,
  em vez da transição prevista na tabela "Stage". O gate verde e o estágio
  estão registrados em commit.
- A prova red foi feita com `node --test`, embora o binding de `AGENTS.md`
  indique `gate.ps1 -TestsOnly` para o stage 2. Esta revisão repetiu a prova
  contra a base e executou o gate completo, confirmando a falha funcional.
- O registro do stage 2 antecedia os comentários históricos, contrariando
  a ordem de append de `docs/agents/issue-tracker.md`. A revisão moveu esse
  bloco para depois da triagem, preservando seu conteúdo.

Essas notas não quebram os critérios, os Primary files ou a separação entre
testes e implementação que determina a reabertura no fluxo.

## Spec

Zero falhas dos critérios numerados 1–5, zero escopo extra. PID e exe são
registrados antes da espera pelo CDP; a identidade do processo é conferida;
o relançamento usa a rotina WMI com ambiente limpo; sem registro, a rotina
anterior permanece coberta pelos testes.

Duas observações para triagem, reproduzidas contra `qa/down.ps1` real com
cmdlets de processo/WMI simulados:

- [CLEAN-028](../qa/issues/16-CLEAN-028-down-recupera-falha-na-restauracao.md):
  após falha WMI no relançamento, a repetição consome o PID já morto e deixa
  o PC fechado. O critério 3 exige, para um registro morto, "não mata nem
  lança nada, e apaga o registro". Distinguir restauração pendente é um
  requisito adicional, não uma falha desse critério.
- [CLEAN-029](../qa/issues/17-CLEAN-029-down-preserva-exe-real-de-debug.md):
  com emulador ativo e registro apontando para `DebugExe`, a limpeza antiga
  encerra o PC recém-relançado. A prosa pede "com ou sem `emulator.json`
  ativo", mas o critério 2 restringe-se a emulador ausente/inativo. A rotina
  anterior já encerrava esse exe nesse estado; não há regressão do
  comportamento anterior nem falha do critério numerado.

Examinados: rollback de `qa/up.ps1`, starts isolado/não isolado, falhas do
launcher/CDP, estados ausente/inativo/ativo, PID morto/exe divergente,
ambiente herdado, repetição, erros de adb/AVD/kill/timeout e interação com
o autostart do SYN-030. O start real foi conferido por leitura, como o
ticket determina. Concorrência e corrupção de estado não foram exercitadas.

## Critérios e provas

| Critério | Veredito | Evidência |
| --- | --- | --- |
| 1 — registro do start real | ✅ | Leitura de `qa/pc.ps1:179–185`: guarda `-not $Isolated`, caminho fixo independente de RunDir, PID inteiro e exe resolvido; gravação anterior à espera pelo CDP. Nenhum start não isolado foi executado. |
| 2 — restauração sem emulador ativo | ✅ | `real-live` e `real-inactive`: PID substituído, mesmo exe, uma trace de lançamento com as três variáveis de isolamento/CDP vazias e registro consumido. `real-active` também passou com o exe de release. |
| 3 — morto ou exe divergente | ✅ | `real-dead` e `real-mismatch`: registro consumido, nenhum relançamento e processos não correspondentes preservados. |
| 4 — sem registro | ✅ | Os nove cenários anteriores passaram, incluindo os quatro erros do emulador, ausente/inativo, `realWasRunning: false` e repetição sem duplicar o PC. |
| 5 — documentação | ✅ | `docs/agents/qa.md` descreve a restauração ao fim do pós-run, o caminho fixo e o comportamento sem emulador ativo. |

- Red desta revisão: testes de `7269ebf` com `down.ps1` da base em uma árvore
  local ignorada; saída 1, 15 testes, 9 passaram e 6 falharam. Falharam os
  cinco cenários novos e o teste pai: falta de restauração ou de consumo
  do registro. Testes chamam produção, com executáveis Windows temporários.
- Green: gate completo em primeiro plano, saída 0, `gate: passou`.
  Rust 83/83, interface 61/61, QA 78/78, incluindo 15/15 do `down`;
  fmt, Clippy e TypeScript passaram. Gradle `check`: `BUILD SUCCESSFUL`,
  41 tarefas, 1 executada e 40 up-to-date, com testes Android reutilizados.
- Logs locais ignorados: `clean-024-stage3/red.log`, `gate.log` e
  `repro.log`; o harness adicional está em `clean-024-stage3/repro.ps1`.

## Kit no emulador e limpeza

O primeiro `up` falhou ao executar o adb do caminho padrão. A tentativa
com o SDK de `android/local.properties`, `D:/AppData/Local/Android/Sdk`,
saiu 0: AVD `SynchroNice_API37`, endpoint `127.0.0.1:5555`, PC debug com
CDP 9222 e dados em `clean-024-stage3/emulator/data`. O build de debug do
PC levou 7 minutos; Android `assembleDebug` passou.

`up` retornou `online: true`; uma chamada independente de `presence`
retornou `{"status":200,"online":true}`. O start isolado não criou o
registro fixo do PC real. O PC real estava fechado antes da preparação.

`down`, com o mesmo RunDir e SDK, saiu 0 e retornou
`{"restored":false,"stopped":true}`. Repetição: saída 0 e mesmo JSON.
O PID isolado 14924 deixou de existir, o emulador desapareceu de
`adb devices` e `emulator.json` terminou com `active: false`.

Nenhum arquivo de usuário foi criado ou apagado. Os testes encerraram e
removeram seus stubs temporários; dados e evidências isolados foram
preservados. A mudança de finais de linha em `pc/src-tauri/Cargo.toml`
gerada pelo build Tauri foi desfeita. O Redmi não foi acionado.
Não há débito humano novo: o critério 1 tem verificação por leitura
expressamente aprovada, e os demais estão comprovados pelas provas acima.

Totais: Standards 4 notas processuais, 1 observação de nome, 0 bloqueios;
Spec 0 falhas dos critérios e 2 observações para triagem separada.
