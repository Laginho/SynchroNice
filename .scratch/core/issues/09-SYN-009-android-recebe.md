# SYN-009: Android recebe arquivos do PC
Stage: to-implement
Status: ready-for-agent
Blocked by: SYN-007, SYN-004
Review: agent

- Primary files:
  - android/** (download de Item de arquivo, cache de miniaturas, bolha de arquivo com progresso, ação de salvar)

#### What to build

Itens de arquivo mandados do PC aparecem no chat do celular como bolha à
esquerda. Imagem mostra miniatura carregada do cache do app, sem ir para a
galeria. Outro tipo mostra nome, extensão e tamanho. Durante o download a
bolha mostra progresso animado. Um toque na bolha salva: imagem vai para a
galeria, outro arquivo para Downloads, e a bolha indica que foi salvo. Um
toque em texto que é link abre o navegador.

#### Acceptance criteria

1. Uma imagem enviada do PC aparece como miniatura no celular sem aparecer na galeria.
2. Um toque na miniatura salva a imagem na galeria e a bolha indica "salvo".
3. Um PDF enviado do PC aparece com nome, `PDF` e tamanho; um toque o salva em Downloads.
4. Durante o download de um arquivo grande a bolha mostra progresso animado.
5. Um link enviado do PC abre no navegador ao tocar.
6. Fechar o app descarta as bolhas; o que foi salvo permanece na galeria ou em Downloads.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Critérios 1 a 6: manual, com PC real.

## Tests stage 2 writes (own commit, red)

- `android/.../test/ProtocolClientTest.kt`: ao receber anúncio de Item de arquivo, o cliente baixa pela rota de download com progresso crescente e entrega bytes idênticos; download interrompido não marca o Item como completo. Vermelho porque o download não existe.

## Comments

- 2026-09-20 (revisão de SYN-004): **a cadência de reconexão do celular é o
  que decide o indicador de presença do PC, e ela é deste ticket.** O
  `GET /listen` do PC (`pc/src-tauri/src/server.rs`) é long-poll que fecha
  depois de entregar: bloqueia até haver ao menos um Pendente, devolve todos
  os de agora (um JSON por linha) e encerra. A presença do PC é a contagem de
  conexões `/listen` vivas, então **cada entrega derruba a presença até o
  celular reabrir** — o ponto verde do PC pisca a cada envio. Se este cliente
  reabrir o `/listen` imediatamente depois de ler a resposta, o piscar fica
  imperceptível; se esperar, o critério 8 do SYN-004 fica feio na prática
  mesmo estando satisfeito na letra. O simétrico também é deste ticket: um
  celular que some sem fechar o TCP deixa o contador do PC em 1 e a presença
  mentindo "na rede" até o socket estourar, porque o handler está só
  aguardando o próximo Pendente. Vale um keep-alive ou um timeout curto no
  lado do cliente.
- 2026-09-20 (revisão de SYN-004): o protocolo que este cliente consome é
  `GET /listen` (anúncio), `GET /outbox/{id}/file` (bytes) e
  `POST /outbox/{id}/ack` (confirmação) — **só o `ack` remove o Item da fila
  de Pendentes do PC**, nunca a entrega em si, e o `ack` é idempotente (204
  mesmo para id que já saiu). Reenviar a confirmação é inofensivo; deixar de
  enviá-la faz o PC reentregar o mesmo Item, então o cliente precisa ignorar
  id já recebido (spec, "Protocolo de Itens").
