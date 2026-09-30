# SYN-020: Bandeja de envio no PC antes de mandar
Status: needs-triage
Blocked by: none

Sem `Stage:` de propósito: precisa de stage 1 com o humano antes de entrar no loop.

Pedido no walkthrough de QA (2026-09-30). Hoje, Ctrl+V de imagem ou arquivo, e
o drop, enfileiram na hora. O pedido é o jeito do WhatsApp: o que é colado ou
solto fica numa bandeja acima do composer, dá para juntar texto, mais imagens e
documentos, tirar o que entrou por engano, e só o Enviar manda tudo. Cada coisa
continua sendo um Item separado (a spec proíbe agrupar), mas saem juntas.

Decisões em aberto:
- O drop também passa pela bandeja, ou só o Ctrl+V?
- A bandeja sobrevive a fechar a janela (vai para a bandeja do sistema) ou
  morre com ela?
- Ordem de envio: a da bandeja, com o texto por último?
- Esc limpa a bandeja?
