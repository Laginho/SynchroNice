# SYN-026: Kit de QA no loop — stage 3, `proxy` e pós-run do foreman
Stage: done
Status: ready-for-agent
Blocked by: SYN-024, SYN-025
Review: human

- Primary files:
  - AGENTS.md (bindings e a seção "Humano simulado")
  - docs/agents/qa.md (só a seção de quem usa o kit e quando)

#### What to build

Spec: `.scratch/qa/spec.md`, histórias 29–33, e a seção "Implementation
Decisions" (O destino do ❌, Débito, Integração).

O loop passa a usar o kit, com as regras escritas só neste repo. Nada muda na
`ticket-flow`, no `proxy.md`, no `sweatshop` ou no `foreman` globais.

- **Bindings:** uma linha aponta `docs/agents/qa.md`. O stage 3 usa o kit no
  emulador (`up`, depois `down` se for o último) como evidência dos critérios
  visíveis. Um ❌ reprova a revisão do próprio ticket. Uma falha de
  infraestrutura não reprova.
- **"Humano simulado":**
  - O `proxy` tenta o kit antes de registrar `Débito humano:`.
  - Quando o kit verifica, não se registra débito, e o comentário aponta o
    relatório.
  - A linha do Computer Use do Codex continua valendo para o que o kit não
    alcança.
- **Foreman:**
  - No fim do run, se o `adb devices` mostrar o Redmi pelo transporte sem fio,
    ele roda no Redmi os critérios visíveis dos tickets do run mais os passos
    ★ do walkthrough. O relatório vai para o resumo, e cada ❌ vira `BUG-*`
    `needs-triage`.
  - Se o Redmi não aparecer, o resumo diz "sem aparelho".
  - Ele roda o `down` no fim do run.

#### Acceptance criteria

1. Os bindings do `AGENTS.md` apontam `docs/agents/qa.md` e dizem quem usa o
   kit (stage 3 e `proxy`, no emulador) e o que um ❌ faz.
2. A seção "Humano simulado" diz que o `proxy` tenta o kit antes de registrar
   débito, e que um passo verificado pelo kit não vira débito.
3. A seção "Humano simulado" diz o que o foreman faz no fim do run: o Redmi
   com os critérios do run e os ★, ou "sem aparelho", e o `down`.
4. `docs/agents/qa.md` e o `AGENTS.md` não se contradizem. Os usos ficam no
   `qa.md`, e o `AGENTS.md` só aponta.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Tests stage 2 writes (own commit, red)

Nenhum: o ticket altera apenas documentação.

## Comments

- Stage 2 (2026-10-01): SYN-024 e SYN-025 estão `done` na base de sessão
  `sweatshop/2026-09-30-2146`. Sem commit de testes red: a seção de testes
  dispensa testes novos porque o escopo é apenas documentação. A verificação
  será a leitura dos quatro critérios e o gate completo.

#### Stage 2 handoff (2026-10-01)

- Critério 1: binding QA aponta `docs/agents/qa.md#quem-usa-o-kit-e-quando`,
  identifica stage 3 e `proxy` no emulador e distingue ❌ de infraestrutura.
- Critério 2: "Humano simulado" exige tentar o kit antes do débito;
  passos verificados apontam o relatório, sem débito. Computer Use permanece
  para o que o kit não alcança.
- Critério 3: o ponteiro do foreman indica Redmi sem fio, critérios do run
  e ★, "sem aparelho" e `down`; o guia detalha relatório, `BUG-*`
  `needs-triage` sem `Stage:` e teardown inclusive após infraestrutura falhar.
- Critério 4: procedimentos ficam na nova seção "Quem usa o kit e quando"
  do guia; `AGENTS.md` traz os ponteiros e os gatilhos resumidos. Nenhuma
  skill global, driver ou código do app foi alterado.
- Verificação: `git diff --check` limpo;
  `powershell -ExecutionPolicy Bypass -File .\gate.ps1` saiu 0,
  `gate: passou`. Rust: 52 testes; UI: 47; kit QA: 14, todos passaram.
  Android: `BUILD SUCCESSFUL`, lint/check verdes, testes `UP-TO-DATE`;
  XMLs existentes somam 121 testes, zero falhas, erros ou skips.
- Sem prova red-green nem execução de critérios visíveis no app: alteração
  exclusivamente documental, conforme a seção "Tests stage 2 writes".

#### Resolution (2026-10-01)

Verdict: Approve

- Revisão de `5d9c424...cc46568`, após rebase sobre
  `sweatshop/2026-09-30-2146`; integração sem squash em `cbaea90`.
  Arquivos: bindings e "Humano simulado" do `AGENTS.md`, e somente a
  seção "Quem usa o kit e quando" de `docs/agents/qa.md`.
  Stage 3 não precisou corrigir a implementação.
- Standards: **0 violações, 0 smells**. Primary files respeitados;
  metadados e histórico do ticket seguem o tracker. Sem conflito com
  `CONTEXT.md`, ADRs ou a decisão local de QA em `docs/DECISIONS.md`.
- Spec: **0 achados**; os quatro critérios estão atendidos:
  - ✅ Critério 1: binding identifica stage 3 e `proxy` no emulador,
    aponta o guia e distingue reprovação por ❌ de infraestrutura.
  - ✅ Critério 2: o `proxy` tenta o kit antes do débito; passo verificado
    aponta o relatório, sem débito. Computer Use permanece para o restante.
  - ✅ Critério 3: foreman usa Redmi sem fio com critérios do run e ★,
    relata "sem aparelho" quando necessário e chama `down` ao terminar.
  - ✅ Critério 4: procedimentos concentrados no guia; `AGENTS.md` contém
    ponteiros e os gatilhos exigidos, sem contradição nem alteração global.
- Testes red-green dispensados expressamente pelo contrato documental;
  nenhum commit altera testes. Os critérios verificam texto, sem critério
  visível no app que exija execução do emulador ou gere débito humano.
- Gate completo em primeiro plano, saída **0**, após rebase: fmt, clippy,
  typecheck e lint verdes; Rust **52**, UI **47**, kit QA **14** passaram.
  Android app **59** e protocol-client **62** `UP-TO-DATE`; XMLs com zero
  falhas, erros ou skips. `BUILD SUCCESSFUL` e `gate: passou`.
  Log local: `.scratch/qa-runs/syn-026-review/gate.log`.
- Resumo dos eixos: Standards **0**, Spec **0**; sem achado em nenhum eixo.
