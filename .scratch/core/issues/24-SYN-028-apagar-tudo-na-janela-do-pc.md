# SYN-028: Apagar tudo na janela do PC
Stage: implementing
Status: ready-for-agent
Blocked by: SYN-019
Review: agent
Difficulty: normal

- Primary files:
  - pc/src-tauri/src/lib.rs (comando de Apagar tudo; evento para a janela ligado ao ouvinte de exclusão do SYN-019)
  - pc/ui/main.ts, pc/ui/styles.css, pc/index.html (🗑, aviso com Desfazer, bolhas sumindo)

#### What to build

O lado da janela do Apagar tudo do PC. O protocolo, a operação de Apagar tudo,
a Lixeira e o ouvinte de exclusão já existem: são do SYN-019. Leia as decisões
de lá antes de começar.

Decisões do stage 1 (2026-10-01; protótipo em
`prototype/syn-019-apagar-tudo`, veredito: o botão da variante B com o
desfazer da variante A):

- **Botão.** Um 🗑 no cabeçalho, à esquerda da ⚙, só visível com o chat não
  vazio.
- **Desfazer.** O clique esvazia o chat na hora e mostra um aviso embaixo,
  acima do composer: "Conversa apagada · N Itens e M arquivos", com
  "Desfazer" e uma barra que esvazia em 5 s. Durante a janela nada é apagado
  nem sai pela rede, então o desfazer só devolve as bolhas. Um segundo clique
  durante a janela fecha a anterior na hora e abre outra. Itens que chegam
  durante a janela ficam e não entram na exclusão. Sair pela bandeja durante a
  janela não apaga nada.
- **Fim da janela.** Um comando Tauri chama o Apagar tudo do SYN-019 com os
  Itens que a janela esvaziou: os recebidos pelo id de `Items`, os enviados
  pela chave da lista de enviados do BUG-003.
- **Do outro lado, sem aviso.** Uma exclusão vinda do celular tira as bolhas
  da janela sem rastro: o `lib.rs` liga o ouvinte de exclusão a um evento, como
  faz com `item` e `enviado`.
- O aviso divide o espaço acima do composer com a bandeja do SYN-020; quem
  entrar depois acomoda os dois.

#### Acceptance criteria

1. Na janela: o 🗑 só aparece com o chat não vazio; o clique esvazia o chat e
   mostra o aviso com as contagens; "Desfazer" em até 5 s devolve as bolhas e
   nenhum arquivo sai da Pasta; sem desfazer, os arquivos saem da Pasta e
   estão na Lixeira.
2. Um Item que chega durante a janela aparece no chat e continua lá depois que
   ela fecha.
3. Recarregar a janela depois do Apagar tudo não traz nada de volta.
4. Uma exclusão vinda do celular tira as bolhas daqueles Itens da janela, sem
   aviso.
5. Os testes existentes seguem verdes.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

QA no stage 3: kit no emulador, critérios 1 a 4 pelo CDP, com a Pasta e a
Lixeira conferidas pelo kit. Nos critérios 2 e 4, aja como o celular por HTTP
(token do `config.json` da execução): mande um texto durante a janela, e
mande `POST /items/delete` com o `X-Item-Id` de um Item recebido.

## Tests stage 2 writes (own commit, red)

- Nenhum. A operação, a exclusão recebida e o ouvinte são testados no SYN-019;
  aqui é a cola Tauri e a interface, verificadas pelo QA. Stage 2 registra isso
  no commit.

## Comments

- 2026-10-01 Proxy decided: nasceu do fatiamento do SYN-019 a pedido do dono. Veio para cá a janela do PC (critérios 10 e 11 do original, aqui 1 e 3), mais o comando Tauri e o evento de exclusão recebida no `lib.rs`; os critérios 2 e 4 deixam explícitas decisões que no original eram só prosa, para cada metade ter o seu lado verificável. Ficaram no SYN-019 o protocolo, o backend, a Lixeira e as docs. Blocked by: SYN-019, que dá a operação e o ouvinte que esta janela chama. Difficulty: normal: interface e cola fina, sem concorrência nem lógica de arquivo. Review: agent, porque as docs ficaram no SYN-019.
