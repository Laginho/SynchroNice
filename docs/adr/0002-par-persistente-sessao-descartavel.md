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
- A **Sessão** (conteúdo do chat) é descartada quando o app encerra de fato
  (sair pela bandeja, reiniciar Windows, Android matar o app). Esconder a
  janela não encerra.
- Arquivos recebidos são gravados na Pasta como arquivos comuns e sobrevivem;
  o app não os gerencia depois.
- Itens ficam **pendentes** no aparelho de origem enquanto o destino não está
  na rede e são entregues sozinhos depois.

## Alternativas rejeitadas

- **Pareamento por uso**: impeditivo de uso, segundo o autor.
- **Histórico persistente**: cognição e manutenção que o autor não quer;
  pode ser adicionado se a persistência de Par tornar isso barato.

## Consequências

- Reconexão exige que o celular encontre o PC na rede local sem intervenção,
  com IP variável. É a parte mais delicada do projeto.
- Sem banco de dados: o estado em disco é só o Par e a fila de pendentes.
- Segurança: rede doméstica, uso pessoal. Só o token do QR autentica a
  conexão; sem cifra. Decisão explícita do autor.
