# CLEAN-008: E-mail inválido não é Copiável
Stage: done
Status: ready-for-agent
Blocked by: SYN-011
Review: agent

- Primary files:
  - testdata/copiaveis.json (novos casos compartilhados)
  - pc/ui/copiaveis.ts (reconhecimento e validação de e-mail)

#### What to build

A revisão do SYN-011 encontrou e-mails sem formato válido destacados como
Copiáveis, embora `.scratch/android/spec.md` peça só formatos que se validam.
O problema está nas posições de ponto da parte local: `a..b@example.com`,
`.abc@example.com` e `abc.@example.com` são aceitos hoje. A regra e os casos
compartilhados devem rejeitar essas entradas inteiras, sem destacar um
subtrecho. O SYN-018 usará os novos casos no Android quando for implementado.

#### Acceptance criteria

1. Os três e-mails malformados acima aparecem em `testdata/copiaveis.json` como
   texto comum, sem Copiável parcial.
2. `segmentarTexto` devolve exatamente esses trechos esperados e continua
   reconhecendo os casos válidos `laginho+pix@example.com` e
   `first.last@example.com`.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1 -TestsOnly
    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Tests stage 2 writes (own commit, red)

- Acrescentar os três casos inválidos e o caso válido com ponto a
  `testdata/copiaveis.json`. O teste existente `pc/ui/copiaveis.test.ts` lê o
  arquivo e deve ficar vermelho porque o parser atual classifica os três
  inválidos como e-mail.

## Comments

- 2026-09-24 Achado fora dos critérios numerados de SYN-011; registrado em
  ticket separado conforme a revisão de estágio 3.

#### Resolution (2026-09-24)
Verdict: Approve

Standards: sem achados. Spec: critérios 1 e 2 atendidos, sem achados.
O commit de testes `1cb4f71` alterou apenas o ticket e
`testdata/copiaveis.json`: `gate.ps1 -TestsOnly` falhou nos três novos e-mails
inválidos (21/24 casos Node passaram), pois todos eram classificados como
Copiáveis. O commit de código `7a450e1` alterou apenas o ticket e
`pc/ui/copiaveis.ts`; depois dele, o gate completo passou com 24/24 casos Node,
39 testes Rust, `tsc --noEmit`, fmt, Clippy e `gradle check`.

Arquivos: `testdata/copiaveis.json` e `pc/ui/copiaveis.ts`. Integrado à branch
de sessão por `b91e1db`.
