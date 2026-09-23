# SYN-015: Android — visualizador de imagem
Stage: to-implement
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
