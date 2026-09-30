# BUG-003: O PC perde os enviados ao recarregar, e a bolha enviada não abre nem arrasta
Status: needs-triage
Blocked by: none

Sem `Stage:` de propósito: precisa de stage 1 com o humano antes de entrar no loop.

Achado do walkthrough de QA (2026-09-30).

1. Recarregar a janela do PC apaga as bolhas enviadas pelo PC. As recebidas
   voltam. O `main.ts` só relista os recebidos (`list_items`), e a bolha
   enviada só nasce de evento (`renderEnviado`). A Sessão é a vida do
   processo (ADR-0002), e recarregar a janela não encerra a Sessão, então é
   bug.
2. A bolha de um arquivo enviado pelo PC (PDF, imagem) não abre com clique nem
   arrasta. A recebida já faz as duas coisas desde o commit a3f6618. A causa:
   depois do ack, `Outbox::confirm` apaga a cópia, e o PC não guarda de onde o
   arquivo veio.

Mesma costura nos dois: o backend precisa lembrar os enviados da Sessão,
inclusive o caminho do arquivo.

Decisões em aberto:
- Onde fica o arquivo enviado depois do ack? Opções: o caminho original (o que
  foi arrastado ou colado do Explorer, que pode sumir), ou uma cópia na Pasta
  (ocupa disco, mas é o que o WhatsApp faz). Uma imagem colada do clipboard
  não tem caminho original.
- Os enviados já entregues também voltam depois de um recarregar? Pela
  ADR-0002, sim.
