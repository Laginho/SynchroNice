# CLEAN-015: Download em andamento conta como presença no PC
Stage: to-implement
Status: ready-for-agent
Blocked by: none
Review: agent

- Primary files:
  - pc/src-tauri/src/server.rs (`get_outbox_file`; os comentários de `Presence` e de `get_listen` que dizem que só o `/listen` é presença)
  - pc/src-tauri/tests/server.rs (um teste novo)

#### What to build

Separado do CLEAN-013 pelo proxy em 2026-09-27: é a costura 1 da spec (o
protocolo HTTP do PC), outra costura que a do laço do Android.

A spec diz que a presença do celular é a conexão de escuta. O `GET /listen`
é um long-poll que responde na hora enquanto houver qualquer Pendente
(`server.rs:618`, com o manifesto inteiro de `outbox.rs:233`), e um arquivo
sendo baixado é Pendente até o ACK, que só vem depois de gravado. Logo,
durante um download o celular não tem como manter uma escuta aberta: cada
`/listen` que ele reabre fecha em milissegundos com o mesmo Item, e ele espera
1,5 s antes do próximo (CLEAN-013). A `Presence` do PC (`server.rs:166`) conta
só guardas de `get_listen`, então durante o download inteiro o PC se acha
sozinho: a janela pisca presente/ausente a cada ~1,5 s (`on_change`,
`lib.rs:254`), e o sondador do Android (`HEAD /listen` a cada 1 s) vê 204 e o
chat do celular mostra "Fora da rede" com o PC na rede.

Uma conexão viva do celular com a fila de saída é presença, seja o `/listen`
ou o próprio arquivo: `get_outbox_file` entra na `Presence` como o `get_listen`
entra. O detalhe que importa: o handler devolve `(StatusCode::OK, bytes)` e o
hyper escreve o corpo depois de o handler retornar, então um guarda que vive
só no handler solta a presença antes de o primeiro byte chegar ao celular. O
guarda tem de viver com o corpo da resposta — `axum::body::Body::from_stream`
sobre os bytes em pedaços (64 KiB, por exemplo), com o guarda movido para
dentro do stream (o closure do `stream::iter` é dono dele e o solta quando o
stream acaba ou o cliente desconecta). Um pedaço só não serve: o stream
termina na primeira leitura e o guarda cai antes de os bytes saírem. `axum`
0.8 e `futures-util` já estão no `Cargo.toml`; `file_bytes` continua lendo o
arquivo inteiro para a memória, como hoje.

401 e 404 não entram na presença: o guarda só é criado quando o corpo vai ser
mandado. Entrar duas vezes (um `/listen` aberto e um download) é o que o
contador da `Presence` já foi feito para aguentar (`server.rs:161-164`).

Fica aceito um vão de até 1,5 s entre o fim de um download e o `/listen`
seguinte do celular (ele está na espera de lote sem nada novo): a janela do PC
pode piscar uma vez por download, no fim, onde antes ficava ausente o
download inteiro. Se incomodar, é um `CLEAN-*` do lado do Android ("acordar o
laço quando um download termina"), não deste ticket.

Sem ADR: a decisão ("a presença é a conexão") não muda, só o conjunto de
conexões que a compõem; os comentários de `Presence` e `get_listen` em
`server.rs` passam a dizer isso.

#### Acceptance criteria

1. Enquanto um `GET /outbox/{id}/file` autorizado está em andamento (o
   cliente já recebeu o status e ainda não leu o corpo até o fim), sem nenhum
   `/listen` aberto, `HEAD /listen` responde 200 e `on_change(true)` disparou.
2. Quando esse GET termina, ou o cliente desconecta no meio, e não há
   `/listen` aberto, `on_change(false)` dispara e `HEAD /listen` volta a 204.
3. `GET /outbox/{id}/file` recusado (401) ou de id inexistente (404) não muda
   a presença nem dispara `on_change`.
4. Os bytes devolvidos continuam idênticos ao arquivo enfileirado
   (`arquivo_enfileirado_e_baixado_pela_rota_devolve_bytes_identicos` e
   `arquivo_de_saida_ilegivel_no_disco_vira_500_nao_404` seguem verdes sem
   mudar), e `head_listen_confirma_presenca_sem_consumir_o_long_poll` também.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Tests stage 2 writes (own commit, red)

Um teste novo em `pc/src-tauri/tests/server.rs`,
`download_em_andamento_conta_como_presenca`, no estilo de
`head_listen_confirma_presenca_sem_consumir_o_long_poll` (sobe o servidor com
`server::spawn` para ter `running.presence`) e dos testes com `TcpStream` cru
(`dois_envios_concorrentes_do_mesmo_nome_preservam_ambos`, `:765`):

- `enqueue_file` de um arquivo grande o bastante para não caber nos buffers de
  socket (8 MiB de `b'X'`).
- `running.presence.on_change` mandando `online` (true e false) para um
  `mpsc::channel`.
- `TcpStream::connect`, escreve `GET /outbox/{id}/file HTTP/1.1` com
  `Authorization: Bearer <token>` e `Connection: close`, lê só a linha de
  status (200) e **segura o socket sem ler o resto**.
- `on_change(true)` chega em até 5 s; `HEAD /listen` (via
  `requisicao_crua_com_timeout`, 2 s) responde 200 — critério 1.
- Solta o socket (drop); `on_change(false)` chega em até 5 s; `HEAD /listen`
  responde 204 — critério 2, ramo "desconecta no meio".
- Mesmo GET com `requisicao_crua` até o fim: corpo com 8 MiB, e depois dele
  `on_change(false)` de novo e `HEAD /listen` 204 — critério 2, ramo "termina",
  e critério 4.
- GET com token errado e GET de id inexistente: o canal de `on_change` fica
  vazio (`try_recv` é `Err`) — critério 3.

Vermelho esperado (`gate.ps1 -TestsOnly`, primeiro plano): o
`recv_timeout` de `on_change(true)` estoura, ou a asserção do `HEAD /listen`
vê 204 durante o GET segurado. Commite o vermelho antes de qualquer gate; o
gate nunca em segundo plano (AGENTS.md).

Depois, o fix em commit só de código em `server.rs` (`get_outbox_file` mais os
comentários). `gate.ps1` completo em primeiro plano, verde, `Stage: to-review`.
Se o `clippy` reclamar de algo no stream, é fix pequeno dentro do Primary
files; qualquer outra falha volta como `blocked` com o nome do teste.

## Comments

- 2026-09-27 Criado pelo proxy ao responder o `blocked` do stage 3 do
  CLEAN-013 (opção (a) daquela pergunta, como ticket próprio). Independente do
  CLEAN-013 no código: os dois entram no mesmo PR de sessão, e a verificação
  à mão dos dois juntos é o `Débito humano:` registrado lá.

- 2026-09-27 Attempt 1 failed: exit 0. Log tail: Running `.\gate.ps1 -TestsOnly` keeps getting blocked before it reaches you for approval — 4 attempts, none actually prompted, just auto-denied. Per AGENTS.md, gate.ps1 is the only allowed path (no `cargo` direct), so I can't route around it. /  / Can you confirm the permission prompt is reaching you, or would you rather grant it another way (e.g. approve manually in your terminal, or trust the workspace)? I've stopped retrying so I don't spam the same denied call — the red test commit (`f4bba21`) is in place on `clean-015`, waiting on this to prove red-before-green.
