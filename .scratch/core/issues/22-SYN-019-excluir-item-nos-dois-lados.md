# SYN-019: Excluir um Item nos dois aparelhos
Status: needs-triage
Blocked by: none

Sem `Stage:` de propósito: precisa de stage 1 com o humano antes de entrar no loop.

Pedido no walkthrough de QA (2026-09-30): tirar uma foto de teste e apagá-la
dos dois aparelhos pelo próprio app, sem ir à Pasta.

Decisões em aberto:
- Protocolo: um Item novo, de exclusão (`delete {id}`), pela mesma fila de
  Pendentes, para chegar mesmo com o outro lado fora da rede? Os ids são do
  remetente: excluir o Item que o outro mandou exige endereçar pelo id dele.
- O que apaga: o arquivo na Pasta ou em `Download/SynchroNice`, a bolha, ou os
  dois? E o texto?
- Excluir "só para mim" e "para os dois", como no WhatsApp, ou só "para os dois"?
- Um arquivo que o usuário já moveu ou renomeou na Pasta: ignora em silêncio?
- UI: menu de contexto no PC e toque longo no Android, com confirmação?
