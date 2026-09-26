# SYN-013: Android — engrenagem e Desparear
Stage: done
Status: ready-for-agent
Blocked by: SYN-012
Review: agent

- Primary files:
  - android/app/** (engrenagem, tela de configurações, tratamento do 401)
  - android/protocol-client/src/main/kotlin/synchronice/protocolclient/PendingQueueStore.kt (descartar a fila)
  - android/protocol-client/src/main/kotlin/synchronice/protocolclient/ProtocolClient.kt (só se o descarte passar por ele)
  - android/protocol-client/src/test/kotlin/**

#### What to build

Spec: `.scratch/android/spec.md`, histórias 5 a 7, e as seções "Engrenagem" e
"App Android" (token recusado).

Uma engrenagem no topo do chat abre as configurações do app. Os tickets
seguintes (SYN-014, SYN-017, SYN-018) acrescentam opções nela. Este entrega a
tela e o **Desparear**: o app esquece o Par, descarta todos os Pendentes (os
arquivos copiados para a pasta interna saem junto) e abre a câmera. Desparear
não avisa o PC.

Se o PC recusar o token (HTTP 401) em qualquer requisição, o app faz o mesmo
que o Desparear, sozinho. HTTP 503 continua sendo "tentar de novo".

#### Acceptance criteria

1. A fila de Pendentes do módulo cliente pode ser descartada inteira: depois do descarte, a fila fica vazia em disco e os arquivos copiados para a pasta interna somem. `File.delete()` pode falhar silenciosamente e deixar a cópia interna para trás. Falha ao excluir uma cópia não pode ser tratada como descarte concluído nem perder a referência necessária para nova limpeza.
2. Um descarte que falha na escrita não deixa a memória e o disco discordando (mesma garantia do enfileirar e do desenfileirar).
3. A engrenagem no topo do chat abre a tela de configurações, com "Desparear".
4. "Desparear" apaga o Par, descarta os Pendentes e abre a câmera; ler um QR pareia de novo. Se apagar o Par persistido falha depois de limpar a fila, o Par é mantido mas os Pendentes já foram perdidos. Falha na gravação que apaga o Par deve manter o Par e os Pendentes antigos juntos.
5. Com o PC re-pareado com outro aparelho, a próxima requisição do celular recebe 401, e o app desfaz o Par e abre a câmera sozinho. O `DeliveryWorker`, dono atual dos reenvios, converte 401 em `Result.retry()`. Também nele, 401 deve descartar Par/Pendentes; se o app estiver fechado, a próxima abertura vai à câmera. HTTP 503 mantém Par/Pendentes e retorna retry.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Critérios 3 a 5 são no aparelho: verificação manual.

## Tests stage 2 writes (own commit, red)

- `android/protocol-client/src/test/kotlin/`: descartar a fila persistida (fila
  vazia ao recarregar, pasta de cargas vazia), e a falha de escrita no descarte
  sem divergência entre memória e disco; falha ao excluir uma cópia interna
  não pode produzir sucesso silencioso com arquivo órfão.
- `android/app/src/test/java/` (JVM local/Robolectric): exercitar o caminho real
  de `AppState.unpair` com Par e Pendentes persistidos. Após sucesso, Par, fila
  e cópias internas somem e novo QR pode parear; se a persistência do Par falhar,
  o Par e os Pendentes antigos permanecem juntos. Contra servidor HTTP falso,
  exercitar o caminho usado por `DeliveryWorker`: 401 descarta Par/Pendentes e
  deixa a próxima abertura na câmera, enquanto 503 conserva ambos e retorna
  retry. A verificação manual continua cobrindo câmera, interface e agendamento
  real do WorkManager no aparelho.

## Comments

- 2026-09-26 Proxy decidiu: aprovar a costura de testes JVM locais do app para Desparear e para a política HTTP do `DeliveryWorker`. Esses casos provam os critérios 1, 4 e 5 já exigidos pela spec; não ampliam o produto para testar câmera ou agendamento do WorkManager no aparelho. Falhas de exclusão ou persistência não podem deixar o Par antigo com fila perdida nem declarar sucesso com payload órfão. Stage 2 retoma na branch SYN-013 com teste vermelho em commit próprio antes da correção.

- 2026-09-26 Red: gate -TestsOnly confirmou 54 testes do protocolo, 1 falha: descarte não lança IOException quando a cópia não pode ser excluída. PC: 44 testes Rust e 24 testes TS passaram.

- 2026-09-26 Review anterior parou antes do veredito por autoidentificação incorreta do modelo; o comando CLI usou gpt-5.6-sol high. Retomar stage 3 da branch SYN-013.
- 2026-09-26 Attempt 1 stopped to ask: SYN-013 ficou `blocked`, registrado no commit `cd2a42d`. Nenhum código alterado. /  / A [skill ticket-flow](C:/Users/bruno/.agents/skills/ticket-flow/SKILL.md) exige parar quando falta uma costura de teste aprovada: “a seam the ticket does not name”. Você autoriza incorporar ao contrato testes do Desparear e do HTTP 401/503 no `DeliveryWorker`?

- Débito humano: no aparelho, abrir a engrenagem, tocar Desparear, ler outro QR e conferir que HTTP 401 volta sozinho à câmera; não havia aparelho conectado nem janela de emulador para inspeção por Computer Use.

#### Review (2026-09-26)

Verdict: Reopen — critérios 1, 4 e 5 precisam de novos testes.

- Spec: testar falha de exclusão do payload, falha entre as duas persistências do Desparear e HTTP 401 no `DeliveryWorker`; 503 continua sendo retry.
- Standards: remover o tratamento morto de 401 no loop que agora só resolve DNS e concentrar a política no caminho que realmente executa cada requisição.
- O Computer Use não encontrou app nem emulador aberto; o débito humano acima permanece.

- 2026-09-26 Stage 2 bloqueado: a revisão pede testes de persistência do Desparear e de HTTP 401/503 no DeliveryWorker, mas a seção Tests stage 2 writes autoriza somente a costura do módulo cliente. A spec também deixa WorkManager fora de teste automático. Decisão necessária de stage 1: aprovar explicitamente a costura de testes do estado do app/DeliveryWorker e incorporar os cenários da revisão ao contrato (Tests stage 2 writes e critérios), antes de retomar a implementação. Nenhum código ou teste alterado nesta tentativa.

- 2026-09-26 Proxy decidiu: aprovar a costura de testes JVM locais do app para Desparear e para a política HTTP do `DeliveryWorker`. Esses casos provam os critérios 1, 4 e 5 já exigidos pela spec; não ampliam o produto para testar câmera ou agendamento do WorkManager no aparelho. Falhas de exclusão ou persistência não podem deixar o Par antigo com fila perdida nem declarar sucesso com payload órfão. Stage 2 retoma nesta branch com teste vermelho em commit próprio antes da correção.

#### Stage 2 — correções da revisão (2026-09-26)

- Critérios 1, 4 e 5 corrigidos. O descarte mantém as referências até concluir a exclusão; falha de escrita do Par preserva Par/fila/cópias. A remoção persistida do Par marca a limpeza pendente para retomada após falha ou reinício, antes de aceitar outro QR.
- `AppState.retryPending`, usado pelo `DeliveryWorker`, despareia em HTTP 401; 503 conserva Par/Pendentes e retorna retry. Removido o tratamento de 401 do loop que só resolve DNS.
- Red-green: `d9a3b31` registrou a falha de exclusão (1 falha em 54 testes do protocolo), corrigida em `2737d12`. `a402d3b` registrou as falhas de persistência/retomada (2 falhas em 6 testes do app), corrigidas em `f073c13`. `6733368` registrou 401 retornando Retry (1 falha em 8 testes do app; 503 verde), corrigido em `f7341c3`. Commits de implementação não alteram testes.
- Gate completo passou: 44 testes Rust, 24 testes TS, 54 testes do protocolo e 8 testes JVM do app; zero falhas. Rust fmt/clippy, TypeScript typecheck e Android lint passaram. `assembleDebug` também passou.
- Débito humano: validar engrenagem, Desparear, câmera/QR e agendamento real do WorkManager no aparelho. Tentativa desta sessão: emulador SynchroNice_API37 iniciado; instalação recusada por `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (assinatura diferente do app existente), sem remover seus dados. Computer Use inicializou e localizou a janela, mas negou acesso com `Computer Use was not approved to use qemu-system-x86_64`. Emulador encerrado após a tentativa. Os caminhos de estado/persistência e execução do worker foram verificados pelos testes JVM.

#### Resolution (2026-09-26)

Verdict: Approve

- Standards: nenhuma violação documentada. Como julgamentos não bloqueantes, `MainActivity.kt` repete a decisão de desparear em quatro caminhos de HTTP 401 e repete a gravação dos três campos de `PairingInfo`; nenhuma refatoração foi incluída neste ticket.
- Spec: critérios 1–5 implementados. O descarte preserva referências quando uma cópia não pode ser excluída; falha ao persistir a remoção mantém Par e Pendentes; HTTP 401 despareia inclusive no `DeliveryWorker`, enquanto 503 conserva a fila para retry.
- Files: tela de configurações e fluxo de Desparear em `android/app`; descarte da fila em `PendingQueueStore`; testes JVM do estado, worker e armazenamento persistido.
- Red-green: `8459c6e`/`0f517d0` cobrem falha ao excluir payload; `36b10d9`/`7fa1c79` cobrem persistência e retomada do Desparear; `5f57171`/`9b28a2b` cobrem 401 e 503 no worker. Os commits de implementação não alteram os testes.
- Gate: após o rebase, `gate.ps1` passou com fmt/clippy, 44 testes Rust, typecheck, 24 testes Node, 54 testes do protocolo Android, 8 testes JVM do app, compilação e lint Android. A primeira tentativa falhou apenas porque o sandbox apontou o Android para `C:\.android`; a repetição fora dessa restrição passou.
- Verificação visual: permanece o `Débito humano` acima para o QA conjunto no aparelho.
