# CLEAN-010: Registrar recibos Android no ADR-0002
Stage: done
Status: ready-for-agent
Blocked by: SYN-014
Review: agent

- Primary files:
  - docs/adr/0002-par-persistente-sessao-descartavel.md (estado mínimo persistido para confirmar arquivo recebido)

#### What to build

Registrar que o Android persiste, ligado ao Par, o recibo mínimo de um arquivo
já gravado enquanto o ACK ainda não foi aceito. O recibo existe apenas para
repetir a confirmação sem baixar outra cópia e é removido após o ACK; não é
histórico da Sessão.

#### Acceptance criteria

1. O ADR inclui o recibo transitório de arquivo recebido entre os estados
   persistidos ligados ao Par.
2. A Sessão continua explicitamente descartável e o texto não sugere histórico
   persistido de chat.

#### Verification

    Revisão do diff de docs/adr/0002-par-persistente-sessao-descartavel.md

## Tests stage 2 writes (own commit, red)

Nenhum: o ticket altera apenas documentação.

## Comments

- 2026-09-26 Achado de Standards na revisão de SYN-014; fora dos Primary
  files daquele ticket.
- 2026-09-26 Stage 2: ADR-0002 registra o recibo transitório ligado ao Par,
  sua remoção após o ACK e a Sessão apenas em memória. Diff revisado contra
  os critérios 1 e 2; sem testes novos conforme o contrato documental.
  Gate completo passou fora do sandbox (44 testes Rust, 32 testes Node;
  Gradle check bem-sucedido, testes Android UP-TO-DATE). A primeira execução
  no sandbox falhou em Rust e no acesso do Gradle a `C:\.android`.

#### Resolution (2026-09-26)

Verdict: Approve

- Standards: nenhum achado; o ADR preserva o vocabulário do glossário e a
  separação entre estado do Par e conteúdo da Sessão.
- Spec: critérios 1 e 2 atendidos. O recibo transitório fica ligado ao Par,
  existe só até o ACK e não restaura o chat.
- Arquivo: `docs/adr/0002-par-persistente-sessao-descartavel.md`.
- Prova: ticket apenas documental, sem testes novos nem etapa red-green,
  conforme `Tests stage 2 writes`. `git diff --check` passou. Após o rebase,
  `gate.ps1` passou fora do sandbox: fmt, clippy, 44 testes Rust, typecheck TS,
  32 testes Node e Gradle check.
