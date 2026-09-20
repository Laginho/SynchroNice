# SynchroNice

Leia `CONTEXT.md` (glossário) e `docs/adr/` antes de mexer no código.
Decisões de processo ficam em `docs/DECISIONS.md`.

## Agent skills

### Issue tracker

Issues vivem como markdown local em `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Os cinco labels canônicos, nome igual ao papel (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` + `docs/adr/` na raiz. See `docs/agents/domain.md`.

## Bindings do fluxo (skill `ticket-flow`)

- Gate: `powershell -ExecutionPolicy Bypass -File .\gate.ps1` (criado por SYN-001; roda typecheck, lint e testes de tudo que existir em `pc/` e `android/`)
- Base branch: `main`
- Models: stage 2 sonnet xhigh, stage 3 opus high
- Times (stage 1 é sempre manual, sweatshop nunca roda). Trocar de time é
  reescrever a linha `Models:` acima — o driver lê a primeira que casar, então o
  menu abaixo não repete a string que ele procura:
  - Time 1 (padrão): sonnet xhigh / opus high
  - Time 2: fable medium / fable max

## Humano simulado (somente neste repo — experimento)

Este repo mede a efetividade do fluxo sem humano no meio. Sobrescreve o
"Hands off" do `foreman` e a regra "a question is a `blocked`" do
`ticket-flow` **apenas aqui**:

- Tudo que o fluxo devolveria ao humano antes do PR de sessão — `blocked` com
  pergunta, `Needs your call`, `Review: human`, passo de verificação manual
  (abrir o app, olhar a tela, testar no celular) — vai para um subagente
  **fable high**, spawnado por quem estiver rodando o `/foreman`. Ele faz o
  papel do humano: lê o ticket e a `spec.md`, decide, e responde do jeito que
  o `ticket-flow` já espera (resposta dobrada no corpo do ticket, `Stage:
  to-implement`, commit na branch de sessão). Depois o foreman relança o driver.
- O que um agente não consegue fazer de fato (abrir o app à mão, ver a tela),
  o fable high contorna — teste automatizado, script, inspeção do binário — e
  registra a revisão humana como débito: uma linha `Débito humano: <o que
  ficou sem verificar à mão>` em `## Comments` do ticket, committed. Nunca
  bloqueia por isso.
- O foreman não para o run por `blocked` esperando humano: spawna o fable high
  e segue. Para só quando não há ticket rodável nem `blocked` respondível.
- O que continua humano: o merge do PR de sessão. O resumo final do foreman
  lista, além dos blocos normais, todos os `Débito humano:` do run
  (`grep -rh 'Débito humano:' .scratch/`), para a revisão ser feita de uma vez.

## Layout

- `pc/` — app Tauri (`pc/src-tauri/` Rust, `pc/ui/` HTML/TS sem framework)
- `android/` — app Kotlin (Gradle)
- `prototype/` — descartável, sai quando o app real existir
- `.scratch/core/` — spec e tickets
