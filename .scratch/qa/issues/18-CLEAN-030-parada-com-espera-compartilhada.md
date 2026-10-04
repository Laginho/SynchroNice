# CLEAN-030: Um só `Stop-AndWait` para os wrappers do kit
Stage: to-review
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
