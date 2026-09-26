# SYN-017: Android — botão de câmera
Stage: to-review
Status: ready-for-agent
Blocked by: SYN-013, SYN-016, SYN-014
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

- 2026-09-26 Stage 2 concluído na branch `syn-017`: botão Tirar foto usa `ActivityResultContracts.TakePicture`, com FileProvider limitado à pasta interna `camera/`, permissão e nome temporário restaurado na recriação da Activity. Confirmar reutiliza `AppState.queueFile`; cancelar apaga o temporário sem enfileirar. A opção persistente `keep-photos` começa desligada; ligada, grava também via MediaStore/SAF na Pasta. O temporário é removido após enfileirar e a cópia da fila é removida pela entrega existente. Falha de gravação/envio mostra erro e preserva a captura interna.
- Validação: `powershell -ExecutionPolicy Bypass -File .\gate.ps1` terminou `gate: passou` (exit 0): Rust 44 testes, Node 32, Android 79 (23 app + 56 protocolo), zero falhas nos relatórios; fmt, clippy, typecheck e lint aprovados. Primeira execução esbarrou no SDK no sandbox; execução elevada compilou/testou Android, mas retornou falha agregada anterior sem causa preservada na saída truncada; repetição completa com log passou. Sem prova red-green nova, conforme dispensa explícita deste ticket.
- Débito humano: validar no aparelho os critérios 1–5 (layout do composer, confirmar/cancelar na câmera, preferência persistente e retenção na Pasta versus limpeza após entrega). Computer Use foi inicializado e consultado, sem emulador/espelhamento disponível; `adb devices` no SDK configurado retornou lista vazia. Compilação, lint e testes existentes não comprovam esses comportamentos no aparelho.

- 2026-09-26 Stage 2: dependências SYN-013, SYN-014 e SYN-016 em done na sessão. Sem novos testes, conforme a seção "Tests stage 2 writes": câmera/interface usam verificação no aparelho; envio reutiliza queueFile e a fila já testada.

- 2026-09-26 Proxy decidiu: a opção "Guardar fotos tiradas" usa a Pasta entregue pelo SYN-014. A dependência agora está em `Blocked by`; aguardar SYN-014 chegar a `done` na branch da sessão antes do stage 2 deste ticket. Nenhuma costura nova é necessária: o envio continua pelo SYN-016 e o destino pela Pasta do SYN-014.

- 2026-09-26 Attempt 1 failed: exit 0. Log tail: SYN-017 ficou `blocked`: guardar fotos depende da Pasta do SYN-014, ainda bloqueado e sem implementação integrada. /  / Registro no commit `c30eacd`, branch `syn-017`. Nenhum código alterado. /  / A [skill ticket-flow](C:/Users/bruno/.agents/skills/ticket-flow/SKILL.md) exige parar quando “the ticket needs a seam it does not name”. Próximo passo: incorporar SYN-014 como dependência e integrá-lo antes de retomar.

- 2026-09-26 Attempt 2 failed: exit 0; blocked after two attempts. Log tail: SYN-017 ficou `blocked`: guardar fotos depende da Pasta do SYN-014, ainda bloqueado e não integrado. /  / Registro: commit `711e80f`, branch `syn-017`. Nenhum código alterado. /  / A [skill ticket-flow](C:/Users/bruno/.agents/skills/ticket-flow/SKILL.md) exige parar quando falta “a seam the ticket does not name”. Próximo passo: resolver e integrar SYN-014 antes de retomar.
