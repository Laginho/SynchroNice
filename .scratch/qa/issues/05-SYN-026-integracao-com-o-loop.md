# SYN-026: Kit de QA no loop — stage 3, `proxy` e pós-run do foreman
Stage: to-implement
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
