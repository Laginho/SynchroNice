# SYN-002: PC recebe texto pela rede
Stage: reviewing
Status: ready-for-agent
Blocked by: SYN-001
Review: agent

- Primary files:
  - pc/src-tauri/src/** (servidor HTTP, lista de Itens em memória, token)
  - pc/src-tauri/tests/** (New: server.rs)
  - pc/src-tauri/Cargo.toml (dependência do servidor HTTP)
  - pc/ui/** (renderizar bolha de texto à esquerda)

#### What to build

O PC vira servidor. Sobe um serviço HTTP na rede local numa porta fixa, com um
token secreto gerado e gravado na configuração (o QR que o expõe vem em
SYN-005). Uma requisição com o token e um corpo de texto cria um Item de texto
que aparece como bolha à esquerda no chat, com hora. Sem token ou com token
errado, recusa. Links na bolha abrem no navegador ao clicar.

#### Acceptance criteria

1. Com o app aberto, `curl` com o token e um texto faz a bolha aparecer no chat em menos de 1 s.
2. A mesma requisição sem token, ou com token errado, recebe recusa e nada aparece no chat.
3. Um texto contendo HTML aparece como texto literal, nunca renderizado.
4. Um texto que é URL vira link clicável que abre no navegador padrão.
5. Dois textos enviados em sequência aparecem em ordem de chegada.
6. Sair pela bandeja e reabrir mostra o chat vazio (Sessão descartada); o token permanece o mesmo.

#### Verification

    curl -X POST http://localhost:<porta>/items/text -H "Authorization: Bearer <token>" -d "olá"
    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Critérios 1, 4 e 6: manual.

## Tests stage 2 writes (own commit, red)

- `pc/src-tauri/tests/server.rs`: sobe o servidor em porta aleatória com token conhecido; POST de texto com token devolve sucesso e a lista de Itens contém o texto; POST sem token devolve 401 e a lista fica vazia; dois POSTs preservam ordem. Vermelho porque o servidor não existe.

## Comments

- 2026-09-17 (revisão de SYN-001): o passo `tsc --noEmit` do gate hoje não
  checa nada. `pc/tsconfig.json` tem `include: ["ui"]` e `pc/ui/` só contém
  `styles.css` e `vite-env.d.ts` — zero TypeScript, e `pc/index.html` não tem
  `<script>`. O critério 7 de SYN-001 está cumprido na letra, mas o passo só
  passa a valer quando este ticket adicionar o primeiro `.ts` e o `<script>`
  que o carrega. Vale conferir que o gate fica vermelho com um erro de tipo de
  propósito antes de confiar nele.

- 2026-09-19 (stage 2, branch `syn-002`):
  - **Red-green.** Testes em `aa15254` (só testes + `Stage: implementing`):
    `tests/server.rs` não compila porque `pc_lib::items` e `pc_lib::server`
    não existem (E0432); `tests/config.rs` não acha `load_or_create_token`
    (E0425). `d51dce7` é só o rustfmt do arquivo de teste. Código em
    `90a7b58`, sem tocar em `tests/`. Verde: 8 testes (4 em `config.rs`,
    4 em `server.rs`), gate `passou`.
  - **Protocolo.** `POST /items/text`, corpo UTF-8, `Authorization: Bearer
    <token>`; 201 com `{"id": n}`; 401 sem token ou token errado. Porta fixa
    `47820` (`server::PORT`), bind em `0.0.0.0`. O Windows pode pedir
    liberação no firewall na primeira subida.
  - **Token.** Gerado uma vez (16 bytes aleatórios em hex) e gravado em
    `config.json` ao lado da Pasta — no Windows,
    `%APPDATA%\dev.synchronice.pc\config.json`. Reler o arquivo é como obter o
    `<token>` para o `curl` até o QR existir (SYN-005).
  - **Critérios.** 2 e 5: teste automático. 3: por construção
    (`textContent`, nunca `innerHTML`). 1, 4 e 6: manuais, ninguém observou
    ainda — precisam de `npx tauri dev` em `pc/`, `curl` do Verification com
    a porta 47820 e o token do config, e sair pela bandeja e reabrir.
  - **Fora dos Primary files:** `pc/index.html` ganhou a linha `<script
    type="module" src="/ui/main.ts">`. É o entry do Vite para a UI que os
    Primary files chamam de `pc/ui/**` (o próprio comentário acima previa "o
    `<script>` que o carrega"). Nada mais fora da lista.
  - **Gate do tsc agora morde.** Com `const erroDeTipo: number = "texto"`
    acrescentado em `ui/main.ts`, o gate imprime
    `error TS2322 ... FALHOU: tsc --noEmit (pc/ui)` e sai vermelho. Revertido.
    Responde ao comentário de 2026-09-17.
  - **Sem mudança de capability:** abrir link usa `openUrl` do
    `tauri-plugin-opener`, cujo `opener:default` já libera `http`/`https`.
  - `npx vite build` empacota `main.ts` (3 chunks, 207 ms).
