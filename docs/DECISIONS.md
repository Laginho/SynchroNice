# Decisões de processo

- 2026-09-16 — **UI: `/prototype` antes, `/impeccable` depois.** Primeiro um
  HTML descartável do chat (largura de celular e de PC) para validar o fluxo.
  Só depois, no app real, polimento visual com `/impeccable`. Registrado a
  pedido do autor, que ainda não usou nenhuma das duas skills.
- 2026-09-16 — Interações de entrada no PC: arrastar do Explorer, Ctrl+V de
  imagem e de arquivo. Sem botão de anexar. Vários arquivos compartilhados de
  uma vez viram Itens separados, nunca agrupados. Arquivo grande mostra
  progresso animado na bolha. Nome repetido na Pasta vira `nome (1).ext`.
- 2026-09-16 — Protótipo de UI em `prototype/chat-prototype.html` (descartável).
  Três variantes: A bolhas esquerda/direita, B feed de cartões com o mais novo
  no topo, C texto e arquivos em painéis separados. Veredito: pendente.
  Quando uma vencer, anotar aqui qual e por quê; o arquivo sai do repositório
  principal e vai para um branch descartável.
- 2026-09-16 — **Veredito do protótipo: variante A (bolhas).** Lados relativos,
  como numa conversa: "eu" à direita, "o outro" à esquerda, em ambos os
  aparelhos. Variantes B e C descartadas. Protótipo atualizado para refletir.
- 2026-09-17 — **Gate do repositório: `gate.ps1` na raiz (SYN-001).** Roda
  `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings` e
  `cargo test` em `pc/src-tauri`, e `tsc --noEmit` em `pc/ui` (via
  `pc/tsconfig.json`); roda `./gradlew check` em `android/` quando esse
  diretório existir. Sai com código diferente de zero se qualquer passo
  falhar. Skills e sessões de agente devem chamá-lo pelo caminho do binding
  em `AGENTS.md`, nunca reimplementar os passos.
