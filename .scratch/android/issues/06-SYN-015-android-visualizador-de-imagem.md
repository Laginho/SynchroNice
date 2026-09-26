# SYN-015: Android — visualizador de imagem
Stage: blocked
Status: ready-for-agent
Blocked by: SYN-014
Review: agent

- Primary files:
  - android/app/** (visualizador e o toque na bolha de imagem)

#### What to build

Spec: `.scratch/android/spec.md`, história 31, e a seção "App Android"
(visualizador).

Tocar numa bolha de imagem, recebida ou enviada, abre a imagem em tela cheia
dentro do app, sem abrir a galeria. Pinça dá zoom, e arrastar move a imagem
ampliada. "Voltar" fecha e volta ao chat. Outros tipos de arquivo continuam
abrindo no app padrão.

#### Acceptance criteria

1. Tocar numa imagem recebida abre a tela cheia dentro do app.
2. Pinça amplia e reduz; arrastar move a imagem ampliada.
3. "Voltar" fecha o visualizador e o chat está como estava.
4. Tocar num arquivo que não é imagem continua abrindo no app padrão.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Todos os critérios são de tela: verificação manual.

## Tests stage 2 writes (own commit, red)

- Nenhum. É só interface, e a spec põe interface fora do teste automático.
  Stage 2 registra isso no commit e segue direto para o código.

## Comments

- 2026-09-26 Attempt 1 failed: exit 0. Log tail: SYN-015 ficou `blocked` na branch `syn-015`, commit `2f88413`. /  / Imagens enviadas são apagadas após a entrega; visualizá-las exige definir retenção e testes no contrato. Nenhum código foi alterado. /  / A [skill ticket-flow](C:/Users/bruno/.agents/skills/ticket-flow/SKILL.md) determina: “Stop only if the ticket needs a seam it does not name”. Registrei a decisão necessária no ticket para Stage 1.

- 2026-09-26 Attempt 2 failed: exit 0; blocked after two attempts. Log tail: SYN-015 ficou `blocked` na branch `syn-015`, commit `50e7c68`. Imagens enviadas são apagadas após a entrega; falta definir retenção ou limitar o ticket às recebidas. /  / A [skill ticket-flow](/C:/Users/bruno/.agents/skills/ticket-flow/SKILL.md) exige: “Stop only if the ticket needs a seam it does not name”. Essa decisão continua fora do contrato. Nenhum código foi alterado.
