# CLEAN-010: Registrar recibos Android no ADR-0002
Stage: to-implement
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
