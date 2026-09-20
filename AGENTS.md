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

## Layout

- `pc/` — app Tauri (`pc/src-tauri/` Rust, `pc/ui/` HTML/TS sem framework)
- `android/` — app Kotlin (Gradle)
- `prototype/` — descartável, sai quando o app real existir
- `.scratch/core/` — spec e tickets
