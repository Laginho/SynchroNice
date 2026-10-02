# Handoff — SynchroNice, pendências pós-run do sweatshop

Data: 2026-09-23 · Repo: `D:\Desktop\Projects\SynchroNice` (remote `Laginho/SynchroNice`)
Branch da sessão: `sweatshop/2026-09-19-2324` · PR: https://github.com/Laginho/SynchroNice/pull/3 (aberto)

O run terminou: 13 tickets merged, 139 commits, 54 stages. Depois dele, uma
sessão numa outra máquina empurrou `b13d64a` na mesma branch — seis correções de
defeitos do PR #3 e a resolução de CLEAN-006. O que sobrou aqui é fila de
decisão e de verificação, não código a escrever. Detalhe de cada item vive nos
tickets em `.scratch/core/issues/` e em `docs/pr-3-review-follow-up.md`; este doc
só aponta.

## Contexto que não está em arquivo nenhum

Este repo roda sob a regra **"Humano simulado"** do `AGENTS.md` (seção própria):
tudo que o fluxo devolveria ao humano no meio do run vai para o agente
`proxy` (`~/.claude/agents/proxy.md`), e o que um agente não consegue fazer de fato vira uma linha
`Débito humano:` no ticket. É por isso que a lista abaixo é longa — ela é o
produto desenhado do experimento, não um sintoma de run malfeito.

## Pendências ("Sua vez")

> **Atualização 2026-09-23 (tarde):** 1 e 2 feitos (PR #3 merged em `7aa8b0f`,
> CLEAN-006 `done`). 4 fechado pelo dono: ele abriu o app e fez a verificação
> manual, está tudo ok. 3 resolvido pela troca de máquina: esta máquina já tem
> o SDK (`android-37`, `local.properties` apontando para ele) e o gate passa.
> Falta abrir os tickets da casca Android. 5: as branches locais já saíram.

### 1. Merge do PR #3
Única coisa que a regra do repo mantém explicitamente humana. Ler antes
`docs/pr-3-review-follow-up.md`: ele lista as seis correções de `b13d64a`, o que
ficou de fora (o app Android continua adiado) e uma **proposta de resposta do
dono** para as cinco entradas `Needs your call` do PR. Aceitar ou reescrever essa
proposta é decisão sua.

### 2. CLEAN-006 — já decidido, falta o stage 3
`.scratch/core/issues/15-CLEAN-006-x-file-size-mentiroso.md` — hoje
`Stage: to-review`, `Status: ready-for-agent`. **Não precisa mais de decisão de
produto.** A sessão de `b13d64a` escolheu recusar (a opção 1) e implementou:
tamanho menor, maior, zero-com-bytes e ausência do chunk terminador falham; só
tamanho correto **com** terminador passa.

O critério 3 mudou junto e vale conferir na review: aceitar antes do terminador
contradizia o critério 1, porque o servidor não distingue um corpo completo de um
prefixo cujo próximo chunk ainda não chegou. O caminho `Content-Length` mantém a
proteção antiga.

Pendente: relançar o driver para o stage 3 revisar e mergear (comando na seção
"Comandos"). Bônus de limpeza: as duas últimas linhas de `## Comments` do ticket
são despejos de log do driver — dizem que CLEAN-006 está travado em
`needs-triage`, o que já não é verdade.

### 3. Android SDK — decisão de máquina
O Android Studio está instalado; o **SDK não** (sem `%LOCALAPPDATA%\Android\Sdk`,
sem `ANDROID_HOME`/`ANDROID_SDK_ROOT`). Só o assistente de primeira execução
baixa (~1–2 GB).

Consequência: SYN-007, SYN-008 e SYN-009 entregaram **apenas** a fatia
Kotlin/JVM (`protocol-client`, testada e verde). A casca do app inteira está
adiada: UI, câmera/QR, mDNS, serviço em primeiro plano, intents de Compartilhar,
galeria/Downloads, notificações. Cada um desses tickets tem a linha
`Débito humano:` dizendo o que ficou fora. `b13d64a` não mexeu nisso.

Decidir: instalar o SDK nesta máquina, ou mover o trabalho Android para outra.
Depois abrir os tickets da casca.

### 4. Ninguém abriu o app — o maior débito do run
Nenhuma verificação visual aconteceu em nenhum ticket, nem no run, nem em
`b13d64a`. Os critérios de tela do PC foram julgados por rastreio servidor→DOM e
por harness jsdom. Uma sessão registrou sem rodeio: *"consertei o bug de
visibilidade do QR às cegas — ninguém abriu este app em cinco tickets"*.

**O roteiro já existe**: `docs/pr-3-review-follow-up.md`, seção "One desktop
verification pass" — seis passos, com arquivos descartáveis, cobrindo pasta+QR+
mDNS, colagem do clipboard, miniatura/progresso/falha, clique vs. arraste,
bandeja e re-pareamento. Rodar aquilo de uma vez fecha a maior parte dos débitos.

O que ele não cobre, e está solto nas linhas `Débito humano:` (lista literal pelo
grep da seção "Comandos"): arrastar bolha de PDF para o navegador, arrastar
imagem para campo de anexo de site real, arrastar bolha ainda em progresso,
clicar num link recebido e ver o navegador abrir, e a escrita atômica do
`persist()` sob crash real.

Cuidado registrado por uma das sessões: rodar o app pela primeira vez registra
`target/debug` no autostart do Windows e abre o diálogo da Pasta sem aviso.

### 5. Limpeza de branches
12 branches já merged, podem sair:
`syn-003` … `syn-009`, `clean-001` … `clean-005`.

### 6. Opcional — diálogo de trust do workspace
`.claude/settings.json` (commit `66ed735`) está **inerte**: as regras
`permissions.allow` são descartadas com `this workspace has not been trusted`
(visível nos `.err` em `.scratch/run-log/`). Aceitar o diálogo de trust
reativaria. Não testado se isso amplia o `--allowedTools` do driver ou não — o
caminho que funciona hoje é `gate.ps1 -TestsOnly`, documentado no `AGENTS.md`.

Também opcional e não aplicado: a edição do `sweatshop.ps1` que leria um binding
`Tools:` do `AGENTS.md` para estender o `--allowedTools`. Nada foi commitado.

## Comandos

Lista literal e completa dos débitos (a branch importa — no `main` não tem):

```bash
git grep -h "Débito humano:" sweatshop/2026-09-19-2324 -- .scratch/
```

Rodar o gate só com testes (o modo que o stage 2 usa):

```
powershell -ExecutionPolicy Bypass -File .\gate.ps1 -TestsOnly
```

Relançar o driver (o rebuild do PATH é obrigatório — `~\.cargo\bin` não está no
PATH das shells do agente):

```
powershell -NoProfile -ExecutionPolicy Bypass -Command "$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User'); & 'C:\Users\Lage\.claude\skills\sweatshop\scripts\sweatshop.ps1' 'D:\Desktop\Projects\SynchroNice'"
```

## Armadilhas já pagas (não repetir)

- **Nunca rodar o gate em segundo plano** dentro de uma sessão do driver:
  `claude -p` morre com o turno e o veredito some no rollback. Três reviews
  morreram assim. A regra está no `AGENTS.md`.
- **Não chamar `cargo` direto** num stage — é negado pelo `--allowedTools`.
  Passar pelo gate.
- PowerShell 5.1: `*> $null` sobre executável nativo vira `NativeCommandError`
  e o `$ErrorActionPreference="Stop"` do gate aborta. Redirecionar por fora.
- O gate se ancora em `android/settings.gradle.kts`, não na pasta `android/` —
  não desfazer, senão uma pasta só com wrapper derruba todos os stages.
- Java: esta máquina tem Java 26 por padrão, que não roda esta versão do Gradle.
  A sessão de `b13d64a` contornou com um JDK 17 portátil e `JAVA_HOME` local ao
  processo, sem mexer no Java do sistema. Se o Gradle falhar, é isso.

## Suggested skills

Para quem pegar isto:

- `standup` — primeiro comando, dá o estado da árvore de tickets e diz o que
  está desbloqueado. Usar a **minha** (`~/.claude/skills`), não a prefixada.
- `foreman` — para relançar e supervisionar o driver no stage 3 de CLEAN-006.
- `ticket-flow` — se algum ticket voltar para stage 1 e precisar de resposta no
  formato que o driver espera.
- `sweatshop` — só se precisar entender o run-log ou o PR de sessão.

## Preferências do ambiente

- Terminal do usuário é **Windows PowerShell 5.1**: `&&` e `||` são erro de
  parse. Um comando por bloco, ou encadear com `;` / `if ($?) { ... }`.
- Colisão de nome de skill: sempre a do usuário (`~/.claude/skills`, fonte
  `D:\Desktop\Projects\Agent-Skills`), nunca a prefixada, sem perguntar.
- Commits terminam com `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`;
  descrição de PR com `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- Beep no fim de um run longo: `[console]::beep(880,200)`.
