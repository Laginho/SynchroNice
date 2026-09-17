# SYN-002: PC recebe texto pela rede
Stage: to-implement
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
