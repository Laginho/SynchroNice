# CLEAN-030: Um só `Stop-AndWait` para os wrappers do kit
Stage: done
Status: ready-for-agent
Blocked by: none
Review: agent
Difficulty: normal
Origin: eixo Standards do stage 3 de CLEAN-026, 2026-10-04

- Primary files:
  - New: qa/process.ps1
  - qa/up.ps1 (só a cópia de `Stop-AndWait` e o dot-source)
  - qa/down.ps1 (idem)
  - qa/pc.ps1 (idem)

#### What to build

`qa/up.ps1`, `qa/down.ps1` e `qa/pc.ps1` definem cópias idênticas de
`Stop-AndWait`: retenção do handle, parada forçada, espera de 10 segundos e
erro de infraestrutura com PID. Essa política já precisou de duas correções
na revisão do CLEAN-026, e cada correção futura teria de ir às três cópias.

Ela passa a viver em `qa/process.ps1`, que define só o `Stop-AndWait`; os
três wrappers fazem `. "$PSScriptRoot/process.ps1"` e apagam a própria cópia.
Mesmo prazo de 10 s, mesma mensagem `infraestrutura: PID <n>: ...`.
`Save-State` e `Get-RecordedPc` ficam onde estão.

#### Acceptance criteria

1. `Stop-AndWait` é definido num único arquivo, carregado pelos três
   wrappers.
2. Cada wrapper continua rodando direto, de qualquer diretório de trabalho.
3. As suítes do kit passam sem mudança.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Tests stage 2 writes (own commit, red)

Nenhum: refatoração pura, sem mudança de comportamento. As suítes atuais são
a rede; o override de `Stop-Process` no `down.test.mjs` continua valendo
porque o escopo do PowerShell é dinâmico. O critério 1 o revisor confere no
diff.

## Comments

- Stage 2 (2026-10-04): extração pura autorizada pelo ticket, sem teste novo
  ou prova red-green. Os chamadores de up, down e pc mantêm a seleção de
  processos, inclusive pipeline vazio, processo já encerrado, falha de stop,
  restauração do PC e limpeza do launcher. A função compartilhada preserva
  integralmente o corpo anterior e o escopo dinâmico de Stop-Process.
- Validação estrutural: corpo de qa/process.ps1 idêntico às três cópias
  na base sweatshop/2026-10-04-1210; apenas um Stop-AndWait definido;
  análise de sintaxe dos wrappers sem erros; git diff --check passou.
  Chamada direta por caminho absoluto a partir de TEMP: up/pc retornam
  o erro esperado para porta 0; down sem estado retorna stopped=true.
- Referência antes da extração: suíte QA no sandbox, 76/96 passaram;
  20 falhas (incluindo dois testes pais) por acesso negado em WMI,
  destino de Lixeira indisponível e dois timeouts de Wi-Fi. Os testes
  de down/pc já tinham terminado antes da edição; phone não foi alterado.
  O gate completo é executado fora do sandbox para validar a integração.
- Gate completo fora do sandbox, em primeiro plano: saída 0, `gate: passou`.
  fmt e clippy aprovados; 83/83 testes Rust, tsc aprovado, 61/61 testes UI,
  96/96 testes QA (incluindo override de Stop-Process e os dois cenários
  de Wi-Fi com timeout na referência). Android `check` aprovado,
  `BUILD SUCCESSFUL`, 41 tarefas (1 executada, 40 up-to-date).
  Suítes existentes intactas; implementação 89c5f97. Diff final conferido:
  somente Primary files e este registro; nenhum artefato gerado incluído.

- Registrado como acompanhamento de manutenção, sem defeito de comportamento
  identificado e sem impedir a aprovação de CLEAN-026.
- Proxy decided: aceito como refatoração pequena (normal, Review: agent); Stop-AndWait num único qa/process.ps1 carregado por up/down/pc via $PSScriptRoot, mesmos prazos e erros, sem teste novo — os wrappers já se chamam por $PSScriptRoot, não há autocontenção a preservar, e a política já precisou de duas correções no CLEAN-026; teste de "não define cópia" seria de organização interna, que a spec exclui.

#### Resolution (2026-10-04)

Verdict: Approve

- Standards: zero achados de código e zero smells. Um desvio formal leve:
  `to-review` foi registrado no handoff `de62095`, separado do último
  commit de código `89c5f97`; gate verde e estágio committed. Registrado
  sem reopen, pois não viola critérios, Primary files ou a exceção de
  testes, nem introduz regressão. Histórico preservado.
- Spec: ✅ critério 1, definição única em `qa/process.ps1` e dot-source nos
  três wrappers; ✅ critério 2, chamadas diretas a partir de TEMP por
  caminhos absolutos, incluindo up/pc/down reais; ✅ critério 3, suítes
  existentes intactas e QA 96/96.
- Arquivos: `qa/process.ps1`, `qa/up.ps1`, `qa/down.ps1`, `qa/pc.ps1`.
  Corpo da função idêntico às três cópias da base por AST, mantendo
  handle, parada forçada, espera de 10 s e erro de infraestrutura com PID.
  Save-State e Get-RecordedPc intactos. Revisão sem correção de produção.
- Examinados: recompilação e seleção do PC em up; identificação dos
  processos, restauração e retentativas em down; start isolado/real,
  launcher e filhos em pc; pipeline vazio, processo encerrado e falha
  de stop. Override dinâmico de Stop-Process confirmado pela suíte.
  Aparelho físico, telas e falhas reais do launcher não exercitados;
  não são critérios visuais deste ticket. Detalhes no relatório.
- `Proxy decided` existente: extração pequena, mesmos prazos/erros,
  Difficulty normal, Review agent e nenhum teste novo de organização
  interna. Implementação conforme; nenhuma nova decisão do proxy.
- Prova red-green nova dispensada expressamente pelo contrato de
  refatoração pura; testes inalterados e comparação estrutural conferida.
  Gate final em primeiro plano, exit 0, `gate: passou`: fmt/Clippy e tsc
  aprovados; Rust 83/83, UI 61/61, QA 96/96. Android check aprovado,
  41 tarefas (1 executada, 40 up-to-date); XML reutilizado com 166 testes
  em 17 suítes, zero falhas, erros ou skips. Primeira rodada registrou
  erro de conexão Windows 10053 em um teste Rust fora do diff; a repetição
  completa passou sem nenhuma mudança de código.
- QA no emulador: up saiu 0 com online=true; pc start encerrou o PID
  anterior antes de abrir o novo, presença HTTP 200 antes/depois e
  list_items via CDP aprovado. Down e sua repetição saíram 0, estado
  active=false, PC isolado e qemu encerrados. PC real inicialmente fechado;
  nenhum arquivo de usuário criado/enviado/apagado no ciclo.
  [Relatório e evidências](../../qa-runs/2026-10-04-emulator-CLEAN-030-review.md).
- Integração local na sessão `sweatshop/2026-10-04-1210`, merge `f8b3169`.
  Ledger atualizado junto de Stage: done. Diff final conferido, sem
  artefatos gerados ou alterações de produção além dos Primary files.
