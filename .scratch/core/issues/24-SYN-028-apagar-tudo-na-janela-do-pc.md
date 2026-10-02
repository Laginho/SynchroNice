# SYN-028: Apagar tudo na janela do PC
Stage: to-implement
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
- 2026-10-02 stage 2: sem testes novos, como o ticket manda (cola Tauri e interface; operação, exclusão recebida e ouvinte são do SYN-019). Gate verde. Interface não exercida no app: fica para o QA do stage 3. Notas: durante a janela de Desfazer o `apagar_tudo` ainda não rodou, então recarregar a janela nesse intervalo devolve as bolhas (nada é apagado); o Desfazer refaz um snapshot (`list_items`/`list_sent_items`) para pôr em dia progresso e entregas ocorridos na janela.

#### Review stage 3 (2026-10-02)

Verdict: Reopen — critérios 1, 3 e 4: eventos e snapshots restauram bolhas já selecionadas ou excluídas.

Diff completo `git diff 7e866dd...09228ef`, comparado à sessão
`sweatshop/2026-10-01-2019`, com os eixos Standards e Spec em subagentes
independentes. Rebase na sessão foi no-op. Todos os achados desta passagem
estão abaixo; nenhum código de produção foi corrigido pelo stage 3.

##### Standards

- ❌ **P1, eventos recriam a seleção durante a exclusão local.**
  `main.ts:662` elimina `janelaDeDesfazer` antes do comando assíncrono
  terminar. Progresso/ACK passa pelos guards em `519`/`625` e recria a
  bolha. O backend local chama `Items::forget` com `notify=false`
  (`server.rs:390,676-679`; `items.rs:263`); o ouvinte ligado em
  `lib.rs:415` não emite `apagado` para essa operação local. Contraria
  Engineering Workflow, quality gate 1 (comportamento solicitado e
  preservação de regressões), e o critério 1. **Reproduzido:** depois dos
  5 s, um chunk real de upload recriou a bolha enquanto o IPC de
  `apagar_tudo` estava retido; após liberar o comando e concluir o upload,
  HTTP 201, backend vazio e uma bolha ainda visível.
- ❌ **P1, snapshot antigo restaura exclusão recebida.**
  `main.ts:702` zera o estado global antes de aguardar os snapshots em
  `712-717`. O evento remove os mapas vivos, mas não a seleção local
  capturada nem os dados do snapshot. A carga inicial em `991-1004`
  também não conserva exclusões ocorridas depois da captura. Mesma regra
  de correção do quality gate 1; critério 4. **Reproduzido duas vezes no
  Desfazer:** reter a resposta real de `list_sent_items`, excluir por HTTP,
  observar zero bolhas e backend vazio, liberar a resposta: uma bolha volta.
- ✅ Sem smells acionáveis ou violação de escopo/test-first. `013433b`
  registra a dispensa expressa de testes novos; `09228ef` muda somente
  os quatro Primary files de produção e o ticket.

##### Spec

- ❌ **P2, critério 1:** “o clique esvazia o chat”. A seleção deixa de
  bloquear eventos quando o timer expira, embora o comando ainda não tenha
  sido aplicado. Falta conservar a seleção durante a aplicação e reconciliar
  o resultado. O caso de progresso acima confirmou a bolha persistente após
  a exclusão bem-sucedida; ACK tem o mesmo guard desarmado.
- ❌ **P2, critério 3:** “Recarregar a janela depois do Apagar tudo não
  traz nada de volta”. O SYN-019 conserva um upload selecionado em `Items`
  até terminar/falhar (`items.rs:248-279`). O snapshot da janela em
  `lib.rs:87-95` o devolve sem filtrar a exclusão, e `main.ts:1003` desenha
  a bolha no reload. **Reproduzido:** expirar os 5 s, confirmar a operação
  com um comando Tauri real e idempotente, recarregar com o upload ainda
  recebendo e concluí-lo. HTTP 201 e backend vazio; a bolha continua
  mostrando 64 de 192 KiB. Falta adaptar a cola/snapshot ao descarte
  diferido já existente, sem mudar a operação do SYN-019.
- ❌ **P2, critério 4:** “Uma exclusão vinda do celular tira as bolhas
  daqueles Itens da janela, sem aviso”. Falta conservar ids excluídos
  durante a reconciliação de snapshots, tanto no Desfazer quanto ao abrir.
  A corrida do Desfazer foi reproduzida duas vezes pelo IPC real; a variante
  da carga inicial foi conferida estaticamente.
- ✅ Critério 2 e fluxo comum de 1/3/4 passaram no executável. Desfazer
  restaura três bolhas e preserva o arquivo; expiração recicla; chegada
  durante a janela permanece; segundo clique finaliza só a seleção anterior;
  exclusão recebida comum remove a bolha sem aviso. Critério 5 verde.

**Validação:** gate completo em primeiro plano, exit 0, `gate: passou`.
Rust: 63 HTTP + 5 unitários + 7 config + 5 outbox = 80; UI 55/55;
kit QA 14/14; fmt, Clippy e TypeScript verdes. Android `check` verde,
testes `UP-TO-DATE`; XMLs com 71 do app e 72 do cliente, sem falhas,
erros ou pulos. Essa suíte não cobre os três casos de UI reproduzidos.

**QA:** [relatório do emulador](../../qa-runs/2026-10-02-syn-028-review.md).
PC debug da branch, emulador pareado, HTTP e CDP reais; o CDP apenas
retém/libera pedidos ou respostas reais para controlar os interleavings.
Dois arquivos da execução confirmados na Lixeira, com origem e SHA-256
idênticos; Pasta vazia, arquivos anteriores do emulador preservados,
`clean` e `down` concluídos. Sem débito humano neste recorte.

**Decisão do proxy conferida:** a única linha `2026-10-01 Proxy decided`
fixa o fatiamento: backend/protocolo/Lixeira no SYN-019 e cola/janela aqui,
com os critérios 2 e 4 explícitos, `Review: agent` e dependência SYN-019.
A revisão respeita esse recorte e não mudou critérios ou decisões.

**Para o stage 2:** fechar os três ❌ e produzir as provas de regressão
de evento durante aplicação, reload de upload selecionado e snapshot
concomitante à exclusão. Os testes do SYN-019 não exercitam esses casos da
janela; a correção precisa de verificação nova e volta ao stage 2 conforme
o ticket-flow. Se a solução exigir ampliar a costura de testes ou o escopo
explicitado de `lib.rs`, consultar stage 1/proxy antes de implementar,
sem reinterpretar os critérios. Preservar os fluxos comuns aprovados e
rodar novamente o gate completo e o QA.

`Stage: to-implement` e esta revisão são committed na branch `syn-028`,
sem merge/push/PR individual. Não havia linha SYN-028 no ledger a retirar.

Resumo por eixo: Standards, 2 falhas de correção (pior: seleção deixa de
proteger o chat antes da aplicação); Spec, 3 falhas (pior: reload mantém
bolha de upload excluído mesmo depois de terminar).
