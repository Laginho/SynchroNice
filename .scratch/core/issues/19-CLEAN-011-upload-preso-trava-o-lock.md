# CLEAN-011: Upload preso trava todos os arquivos do celular
Stage: reviewing
Status: ready-for-agent
Blocked by: none
Review: agent

- Primary files:
  - pc/src-tauri/src/server.rs (rota de arquivo: leitura do corpo e o lock dos uploads com id)
  - pc/src-tauri/Cargo.toml (feature "time" do tokio, para o `tokio::time::timeout`)
  - pc/src-tauri/tests/server.rs

#### What to build

Achado na revisão do PR #7 (sessão `sweatshop/2026-09-25-2127`).

A rota de arquivo do PC segura um lock único para todo upload que traz
`X-Item-Id` enquanto o corpo inteiro chega, e o servidor não tem timeout de
leitura. Desde o SYN-016 o Android sempre manda o id. Se o celular sai do
Wi-Fi no meio de um arquivo, a conexão fica meio aberta, o corpo nunca termina
e o lock nunca é solto: todo arquivo seguinte do celular espera para sempre, e
o cliente vê o PC como inalcançável até o app do PC reiniciar. O comentário
`ponytail:` ao lado do lock fala de gargalo de desempenho, não desse
travamento.

A correção é um timeout de inatividade na leitura do corpo: sem receber bytes
por 30 s, o upload é abortado como um corpo incompleto (o parcial sai da
Pasta, o Item que já nasceu na Sessão termina como falhou — igual a um corte
de conexão, CLEAN-005 —, o id não fica marcado como aceito) e o lock é
solto. O valor fica numa constante que o teste pode encurtar. Particionar o
lock por id fica de fora: com o timeout, o pior caso é um upload esperar o
timeout do anterior.

#### Acceptance criteria

1. Um upload com id cujo corpo para no meio (conexão aberta, sem mais bytes)
   é abortado após o timeout de inatividade: o arquivo parcial não fica na
   Pasta, e o Item que já nasceu na Sessão (a bolha com a barra de progresso)
   termina como falhou, igual a um corte de conexão (CLEAN-005) — nenhum Item
   concluído aparece na Sessão.
2. Um segundo upload, com outro id, enviado enquanto o primeiro está preso,
   termina com 201 assim que o primeiro é abortado, sem precisar reiniciar.
3. Reenviar o mesmo id do upload abortado grava o arquivo (o id não foi
   marcado como aceito).
4. Um upload lento, mas que continua mandando bytes, não é abortado.
5. O comentário `ponytail:` do lock passa a citar o timeout como o limite do
   pior caso.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Tests stage 2 writes (own commit, red)

Em `tests/server.rs`, com o timeout encurtado para o teste:
- upload cru com `X-Item-Id` e `Content-Length` maior que os bytes mandados,
  conexão mantida aberta; um segundo upload com outro id recebe 201 dentro de
  timeout + margem, e a Pasta não tem o parcial (critérios 1 e 2);
- depois disso, o mesmo id do upload preso é aceito e gravado (critério 3);
- upload que manda bytes em intervalos menores que o timeout, somando mais que
  o timeout, termina com 201 (critério 4).

## Comments

- 2026-09-27 Criado a partir da revisão do PR #7 (Standards, bug 1). O stage 1
  foi feito na sessão interativa.
- 2026-09-27 (stage 3) Proxy decided: critério 1 aceita o Item terminar como
  falhou em vez de sumir da Sessão — o timeout é só outra detecção do mesmo
  corpo incompleto do CLEAN-005, e remover o Item exigiria API nova em `Items`
  e esconderia a falha do usuário. Critério 1 e a prosa foram emendados.
- 2026-09-27 (stage 3) Proxy decided: `Cargo.toml` (feature "time" do tokio)
  entra nos Primary files — é consequência mecânica do fix, com precedente no
  SYN-003. Desvio de processo registrado: a linha entrou no commit vermelho
  `eb592a1`, não no de código `b6c131a`; sem reescrita de histórico.
