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
- No Android, um recibo mínimo de arquivo já gravado persiste ligado ao **Par**
  enquanto o ACK não foi aceito, para repetir a confirmação sem baixar outra
  cópia, mesmo após reiniciar o app. O recibo é transitório e removido após o
  ACK; não é histórico da Sessão nem restaura o chat.
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
  na Pasta, o estado persistente é o Par, a Pasta escolhida, a preferência de
  cópia do PC, a fila de Pendentes mantida no aparelho de origem e os recibos
  transitórios de arquivos recebidos no Android, ligados ao Par. A Sessão
  continua apenas em memória.
- Segurança: rede doméstica, uso pessoal. Só o token do QR autentica a
  conexão; sem cifra. Decisão explícita do autor.
