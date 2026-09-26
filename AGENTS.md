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
- **Nunca rode o gate em segundo plano.** Chame e espere terminar, na mesma
  chamada. A sessão do driver é `claude -p`: ela morre quando o turno acaba, e
  não existe "eu retomo quando o gate terminar" — três reviews já morreram
  assim, com o veredito pronto e nada commitado (ver `.scratch/run-log/`,
  `CLEAN-001-review-2038`, `CLEAN-005-review-2124` e `-2147`). Commite o que
  tiver antes de qualquer espera longa.
- Runner de testes (stage 2): `powershell -ExecutionPolicy Bypass -File .\gate.ps1 -TestsOnly`
  — **não chame `cargo` direto**, é negado. O driver só libera `git`, `gh`, `npx`
  e o primeiro token do gate (`powershell`) no `--allowedTools`. O
  `.claude/settings.json` daqui tenta liberar `cargo`, mas está sendo **ignorado**
  porque este workspace não foi marcado como confiável (`this workspace has not
  been trusted`, visível nos `.err` de `.scratch/run-log/`); se alguém aceitar o
  diálogo de trust, resta descobrir se a regra então amplia o `--allowedTools` ou
  não — não foi testado. Até lá, o caminho pelo gate é o que funciona.
  `-TestsOnly` roda só os testes, sem
  fmt/lint/typecheck, que é o que a prova red-before-green precisa; o gate
  completo continua sendo o portão antes de `to-review`.
- Models: stage 2 sonnet xhigh, stage 3 opus high
  (`sonnet` = Sonnet 5, `opus` = Opus 5.5; o driver só aceita o alias, sem
  hífen. Stage 1 é sempre manual, o sweatshop nunca o roda.)
- Models (Codex): stage 2 gpt-5.6-sol high, stage 3 gpt-6-astra medium

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
- O Computer Use do Codex funciona nesta máquina: a falha de 2026-09-24
  (`SetIsBorderRequired … No such interface supported`) era do ambiente, já
  resolvida. Tente-o antes de registrar débito visual; débito é só o que ele
  de fato não conseguiu ver.
- Débito humano se acumula até o app ficar pronto: o humano verifica tudo de
  uma vez, junto com o próprio QA. Não é pendência de run: o resumo do foreman
  lista os débitos, mas não os põe em "Your turn".
- O foreman não para o run por `blocked` esperando humano: spawna o fable high
  e segue. Para só quando não há ticket rodável nem `blocked` respondível.
- Vale para qualquer decisão que o foreman levaria ao humano, não só
  `blocked` — um pormenor de escopo, se um arquivo entra ou não no commit, uma
  anomalia vista num check-in: se um agente pode decidir, o foreman spawna um
  **fable high** (Fable 5.1) como humano provisório, que decide e responde no
  lugar do humano. O foreman só para esperando o humano quando não há como
  seguir sem ele, ou quando a decisão errada é irreversível ou cara/demorada de
  reverter.
- O que continua humano: o merge do PR de sessão. O resumo final do foreman
  lista, além dos blocos normais, todos os `Débito humano:` do run
  (`grep -rh 'Débito humano:' .scratch/`), para a revisão ser feita de uma vez.

## Layout

- `pc/` — app Tauri (`pc/src-tauri/` Rust, `pc/ui/` HTML/TS sem framework)
- `android/` — app Kotlin (Gradle)
- `prototype/` — descartável, sai quando o app real existir
- `.scratch/core/` — spec e tickets do v1 (PC e cliente do protocolo)
- `.scratch/android/` — spec e tickets do app Android, Copiáveis e engrenagem
