# CLEAN-009: Atualizar estado persistido no ADR-0002
Stage: done
Status: ready-for-agent
Blocked by: SYN-011
Review: agent

- Primary files:
  - docs/adr/0002-par-persistente-sessao-descartavel.md (consequência sobre estado em disco)

#### What to build

O ADR-0002 afirma que o estado em disco é só o Par e a fila de Pendentes.
Essa frase ficou desatualizada quando a Pasta escolhida passou a persistir;
SYN-011 acrescentou a preferência "Copiar com pontuação" no `localStorage`
do WebView. Registrar a evolução sem apagar a decisão original de Sessão
descartável e sem alterar o comportamento do app.

#### Acceptance criteria

1. O ADR descreve o estado persistido atual: Par, Pasta escolhida, Pendentes
   e a preferência de cópia, incluindo onde essa preferência é guardada.
2. A distinção entre Sessão descartável e preferências persistentes permanece
   clara, sem sugerir histórico persistido de chat.

#### Verification

    Revisão do diff de docs/adr/0002-par-persistente-sessao-descartavel.md

## Tests stage 2 writes (own commit, red)

Nenhum: o ticket altera apenas documentação.

## Comments

- 2026-09-24 Achado de Standards na revisão de SYN-011; o ADR já estava
  desatualizado antes desse ticket pela Pasta persistente.

#### Resolution (2026-09-24)

Verdict: Approve

- Standards: nenhum achado; o ADR usa os termos do glossário e preserva a decisão original.
- Spec: critérios 1 e 2 atendidos. O ADR nomeia Par, Pasta, Pendentes e a preferência de cópia, situa a preferência no `localStorage` do WebView e distingue esses dados da Sessão em memória.
- Arquivo: `docs/adr/0002-par-persistente-sessao-descartavel.md`.
- Prova: ticket apenas documental, sem testes novos nem etapa red-green, conforme `Tests stage 2 writes`. O diff do ADR e o código de persistência foram revisados; `git diff --check` passou. Após rebase, `gate.ps1` passou: fmt, clippy, 37 testes Rust (6 de config e 31 de servidor), typecheck TS, 24 testes de UI e Gradle check.
