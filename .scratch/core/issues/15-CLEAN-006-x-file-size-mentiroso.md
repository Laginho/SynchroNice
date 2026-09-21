# CLEAN-006: `X-File-Size` mentiroso vira arquivo truncado marcado `done`
Stage: to-implement
Status: needs-triage
Blocked by: none
Review: agent

- Primary files:
  - pc/src-tauri/src/server.rs (só `post_file` — o laço que grava o corpo e a
    tarefa de conclusão)
  - pc/src-tauri/tests/server.rs (os testes novos dos dois casos abaixo)

#### What to build

CLEAN-005 fechou a janela de cancelamento do corpo chunked saindo do laço
assim que `received` alcança o `size` prometido. O efeito colateral é que
`post_file` agora confia no `size` do cabeçalho como se fosse verdade, e ele
não é: `X-File-Size` é um número que o celular manda, no caminho chunked, sem
`Content-Length` para o servidor HTTP conferir.

1. **`X-File-Size` menor que o corpo real.** O laço para no `break`, o resto
   do corpo é descartado, e o arquivo truncado é renomeado para o nome final
   e marcado `Done`. A bolha diz "chegou" sobre um arquivo pela metade — o
   pior dos desfechos, porque nada avisa.

2. **`X-File-Size` maior que o corpo real.** O corpo acaba, o laço termina
   por `stream.next()` devolver `None` sem nunca alcançar `size`, e cai no
   mesmo caminho de conclusão: rename + `Done`, de novo sobre um arquivo
   incompleto. Esse caso é anterior a CLEAN-005 (o `while let` sempre
   terminou assim), mas é a mesma pergunta e merece a mesma resposta.

O caminho com `Content-Length` não tem nenhum dos dois: o corpo do axum já
garante que o número de bytes bate com o cabeçalho.

#### Acceptance criteria

1. Um envio chunked cujo corpo entrega **mais** bytes do que o `X-File-Size`
   anunciado não termina em arquivo com nome final marcado `done`: o Item
   fica `Failed` e não sobra `.part` na Pasta.
2. Um envio chunked cujo corpo entrega **menos** bytes do que o `X-File-Size`
   anunciado, e fecha a conexão normalmente (chunk terminador), termina do
   mesmo jeito: Item `Failed`, sem `.part` e sem arquivo com nome final.
3. O caso honesto (corpo chunked com exatamente `X-File-Size` bytes, conexão
   cortada logo depois do último byte) continua terminando em `Done` — o
   teste `envio_chunked_cortado_apos_ultimo_byte_ainda_termina` de CLEAN-005
   segue verde.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Tests stage 2 writes (own commit, red)

- `pc/src-tauri/tests/server.rs`, na costura HTTP: envio chunked com
  `X-File-Size` menor que o corpo — vermelho hoje porque o arquivo aparece
  no nome final, truncado, com o Item `Done`.
- `pc/src-tauri/tests/server.rs`: envio chunked com `X-File-Size` maior que o
  corpo, fechado normalmente — vermelho hoje pela mesma razão.

## Comments

- 2026-09-20 (revisão de CLEAN-005): aberto pela revisão. O `break` em
  `received >= size` é consequência direta do critério 1 de CLEAN-005 como
  aprovado, então não é correção pequena lá — precisa de teste novo e de uma
  decisão sobre o que fazer com um `size` que mente.
- 2026-09-20: `Status: needs-triage` de propósito — "o que fazer quando o
  corpo não bate com o `X-File-Size`" tem mais de uma resposta razoável
  (recusar com 400 e marcar `Failed`, aceitar o corpo real e corrigir o
  `size` do Item, ou confiar no cabeçalho como hoje), e escolher é stage 1.
  Os critérios acima escrevem a primeira dessas respostas; confirme ou troque
  antes de mandar para stage 2.
