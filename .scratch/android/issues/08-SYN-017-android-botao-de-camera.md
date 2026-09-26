# SYN-017: Android — botão de câmera
Stage: blocked
Status: ready-for-agent
Blocked by: SYN-013, SYN-016
Review: agent

- Primary files:
  - android/app/** (botão no composer, contrato de tirar foto, opção na engrenagem)

#### What to build

Spec: `.scratch/android/spec.md`, histórias 23 a 26, e a seção "App Android"
(Câmera do composer).

Um botão 📷 ao lado do campo de texto abre a câmera do sistema (contrato de
tirar foto). O "✓" da câmera é o envio: a foto entra na fila pelo mesmo caminho
do Compartilhar (SYN-016) e vira bolha à direita. Cancelar na câmera não envia
nada. Só foto, sem vídeo, e continua sem botão de anexar.

A engrenagem ganha "Guardar fotos tiradas", desligada por padrão. Ligada, a
foto também é gravada na Pasta do celular. Desligada, nenhuma cópia fica fora
da pasta interna do app, e ela sai depois da entrega.

#### Acceptance criteria

1. O composer tem um botão de câmera e nenhum botão de anexar.
2. Tirar a foto e confirmar na câmera a envia ao PC, sem uma segunda tela no app.
3. Cancelar na câmera não envia nada.
4. "Guardar fotos tiradas" aparece na engrenagem, começa desligada e persiste.
5. Com a opção desligada, a foto não fica no celular depois da entrega; ligada, fica na Pasta.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Todos os critérios são no aparelho: verificação manual.

## Tests stage 2 writes (own commit, red)

- Nenhum. O envio reusa o caminho do SYN-016, e o resto é interface. Stage 2
  registra isso no commit.

## Comments

- 2026-09-26 Attempt 1 failed: exit 0. Log tail: SYN-017 ficou `blocked`: guardar fotos depende da Pasta do SYN-014, ainda bloqueado e sem implementação integrada. /  / Registro no commit `c30eacd`, branch `syn-017`. Nenhum código alterado. /  / A [skill ticket-flow](C:/Users/bruno/.agents/skills/ticket-flow/SKILL.md) exige parar quando “the ticket needs a seam it does not name”. Próximo passo: incorporar SYN-014 como dependência e integrá-lo antes de retomar.

- 2026-09-26 Attempt 2 failed: exit 0; blocked after two attempts. Log tail: SYN-017 ficou `blocked`: guardar fotos depende da Pasta do SYN-014, ainda bloqueado e não integrado. /  / Registro: commit `711e80f`, branch `syn-017`. Nenhum código alterado. /  / A [skill ticket-flow](C:/Users/bruno/.agents/skills/ticket-flow/SKILL.md) exige parar quando falta “a seam the ticket does not name”. Próximo passo: resolver e integrar SYN-014 antes de retomar.
