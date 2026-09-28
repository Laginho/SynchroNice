# 0002 — Par persistente, Sessão descartável

Data: 2026-09-16 · Status: aceito

## Contexto

Atrito zero é a premissa. Escanear QR a cada uso mataria o uso (o autor cita
o WhatsApp Web expirando como exemplo de "desisto e faço no celular").
Ao mesmo tempo, o autor não quer histórico de chat, banco de dados nem
gerenciamento de arquivos pelo app.

## Decisão

- O **Par** (vínculo PC-celular) persiste indefinidamente após um único QR.
  Só um Par existe; parear de novo substitui.
- A **Pasta** escolhida no PC também persiste entre execuções; seu caminho é
  guardado em `config.json` no diretório de configuração do app.
- A preferência do PC **Copiar com pontuação** persiste no `localStorage` do
  WebView.
- A **Sessão** (conteúdo do chat) é descartada quando o app encerra de fato
  (sair pela bandeja, reiniciar Windows, Android matar o app). Esconder a
  janela não encerra.
- Arquivos recebidos são gravados na Pasta como arquivos comuns e sobrevivem;
  o app não os gerencia depois.
- No Android, um recibo mínimo de arquivo recebido persiste ligado ao **Par**
  enquanto o ACK não foi aceito: a intenção de criar o destino (no SAF, com o
  marcador do nome), o destino em escrita, o rename que tira o marcador e, com
  o arquivo gravado, sua URI. Serve para retomar sem criar outra cópia e para
  repetir a confirmação sem baixar de novo, mesmo após reiniciar o app. O
  recibo é transitório e removido após o ACK; não é histórico da Sessão nem
  restaura o chat.
- No Android também persistem, fora do Par: as preferências **Copiar com
  pontuação** e **Guardar fotos tiradas**, a Pasta escolhida no seletor (com a
  permissão persistente), o último IP em que o PC foi achado (plano B do
  mDNS), se o pedido de permissão de notificação já foi feito e a marca de um
  descarte de Pendentes do Desparear que falhou, refeito antes do próximo QR.
- No PC, os ids dos últimos 1000 arquivos aceitos ficam em
  `accepted-file-ids.txt`, no diretório de configuração: a Sessão some ao
  reiniciar, mas o arquivo fica na Pasta, e o reenvio de uma resposta perdida
  antes do reinício não pode gravá-lo de novo. Ids de texto vivem só com a
  Sessão.
- Itens ficam **pendentes** no aparelho de origem enquanto o destino não está
  na rede e são entregues sozinhos depois.

## Alternativas rejeitadas

- **Pareamento por uso**: impeditivo de uso, segundo o autor.
- **Histórico persistente**: cognição e manutenção que o autor não quer;
  pode ser adicionado se a persistência de Par tornar isso barato.

## Consequências

- Reconexão exige que o celular encontre o PC na rede local sem intervenção,
  com IP variável. É a parte mais delicada do projeto.
- Sem banco de dados nem histórico de chat: fora dos arquivos comuns gravados
  na Pasta, o estado persistente é o Par, a Pasta escolhida em cada aparelho,
  as preferências listadas acima, a fila de Pendentes mantida no aparelho de
  origem, os recibos transitórios de arquivos recebidos no Android, ligados ao
  Par, o último IP do PC no Android e os ids de arquivo aceitos no PC. A
  Sessão continua apenas em memória.
- Segurança: rede doméstica, uso pessoal. Só o token do QR autentica a
  conexão; sem cifra. Decisão explícita do autor.
