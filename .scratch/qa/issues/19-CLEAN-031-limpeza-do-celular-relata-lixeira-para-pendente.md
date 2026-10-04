# CLEAN-031: Limpeza do celular relata Lixeira para arquivo que virou pendente
Stage: done
Status: ready-for-agent
Blocked by: none
Review: agent
Origin: QA da revisão CLEAN-021, 2026-10-03; observação registrada no CLEAN-019 (done)

O `phone.ps1 clean` relatava `Lixeira do MediaStore` quando o caminho original
sumia depois do update (hipótese original do critério 3 do CLEAN-019). No
emulador, arquivos que saíram para `.pending-*`, fora da Lixeira, eram
relatados como Lixeira. A revisão do PR #10 autorizou a correção direta.

Primary files: `qa/phone.ps1`, `qa/phone.test.mjs`.

#### Acceptance criteria

1. A confirmação consulta `is_trashed`, `is_pending` e `_data`, incluindo
   arquivos pendentes e na Lixeira com as duas chaves escapadas. Só um
   `is_trashed=1` confirmado permite relatar `Lixeira do MediaStore`.
2. Caminho original ausente após update sem confirmação, inclusive rename
   `.pending-*`: erro, sem `rm` no derivado, sem destino inventado e sem perder
   o registro em `tracked`. Uma nova limpeza continua exigindo confirmação.
3. A URI e o hash verificado são persistidos antes do primeiro update. Na
   retentativa, antes de qualquer update, a URI guardada precisa apontar ao
   caminho registrado ou a seu rename `.pending-<n>-<nome>`/`.trashed-<n>-<nome>`
   no mesmo diretório, com o mesmo SHA-256, mesmo quando o original existe.
   Caminho não correspondente, arquivo anterior ou bytes alterados ficam
   preservados, com erro e rastreamento mantido.
4. Quando a confirmação volta a funcionar, a retentativa pode confirmar o
   arquivo na Lixeira ou repetir o update sobre a identidade verificada;
   sucesso remove `tracked` e registra o destino real.
5. Lixeira confirmada, fallback Downloads, `rm` do original verificado e
   falha silenciosa de `rm` continuam cobertos. Nada não registrado é apagado.

#### Verification

Gate integrado pelo agente principal; teste focado pela interface pública
`phone.ps1 push/clean` com adb controlado e consulta executada por sh.

## Comments

- Stage 3 do CLEAN-021 (2026-10-03), AVD `SynchroNice_API37`: três payloads
  ASCII sob nomes `.jpg`, criados por adb em `Download/SynchroNice`, foram
  relatados como `Lixeira do MediaStore`. Uma query com inclusão de pendentes
  achou os derivados `.pending-<n>-<nome>`, com `is_trashed=0/is_pending=1`.
  O revisor conferiu GUID e SHA-256, registrou os derivados e os removeu pelo
  wrapper (`rm (MediaStore indisponivel)`); a listagem final voltou aos 12
  caminhos anteriores.
- Não é regressão do CLEAN-021: a inferência pela ausência já estava na base,
  vinda do CLEAN-019. Não se sabe se acontece com arquivos reais recebidos
  pelo app, nem no Redmi: as fixtures não eram JPEG válidos.
- Para triagem: confirmar a Lixeira antes de relatar (por exemplo, a query
  de confirmação incluindo pendentes, ou um `.trashed-*` com o mesmo SHA-256)
  e dar destino próprio, ou `rm`, ao derivado pendente. A solução, os testes
  e o efeito sobre o critério 3 do CLEAN-019 passam pelo stage 1.
- Evidências: [relatório do CLEAN-021](../../qa-runs/2026-10-03-emulator-CLEAN-021-review.md)
  e o JSON local `2026-10-03-emulator-CLEAN-021-review/phone-criteria-evidence.json`,
  campos `mediaStoreConfirmation` e `cleanupFollowup`.

- 2026-10-04, correção direta da revisão PR #10: a expectativa antiga do
  CLEAN-019 (ausência implica Lixeira) foi substituída por confirmação
  positiva. Testes em a71fc99; red pelo gate `-TestsOnly`, exit 1, log
  `%TEMP%/synchronice-pr10-fixes-red.log`: query-error/query-exit/query-empty e
  pending retornavam sucesso com destino falso; a query também não incluía
  pendentes. Nova regressão de URI guardada em 6089387: original ainda
  presente, URI apontando arquivo não relacionado com o mesmo hash; red
  focado mostrou update indevido antes da falha de identidade.
- Implementação: intenção/URI e hash salvos antes da mutação; query incluindo
  pendentes e Lixeira; identidade/caminho/hash conferidos antes de reutilizar
  a URI e antes de confirmar o destino. Falha ambígua mantém o manifesto;
  `rm` continua limitado ao original verificado. Green focado após ambas as
  correções: 9/9, exit 0. Os testes cobrem repetição de erro, recuperação,
  bytes alterados e arquivo não relacionado preservado. Gate integrado e
  evidência nativa coordenados pelo agente principal; nenhum commit ou merge
  realizado pelo subagente.
- QA nativo, 2026-10-04: AVD API37 iniciado diretamente sem janela, sem
  `qa/up` e sem interromper o PC real. PNG válido e payload ASCII `.jpg` em
  Pictures, mais o mesmo payload ASCII em Downloads, foram registrados e
  limpos pelos wrappers. Uma consulta independente incluindo pendentes e
  Lixeira confirmou os três `is_trashed=1/is_pending=0` em `.trashed-*`, com
  SHA-256 igual às fontes. `clean` repetido saiu 0; `tracked` vazio e os dois
  caminhos do baseline Downloads preservados. O rename pendente original
  não ocorreu neste AVD; sua prova de regressão é controlada, não nativa.
  Evidência local em `2026-10-04-pr10-phone/`, sobretudo `mediastore-final.txt`,
  `remote-hashes.txt`, `local-hashes.json` e `final-check.json`. Emulador
  próprio PID 20820 e filho 24700 encerrados após `adb emu kill`; PC real
  PID 18576 permaneceu aberto. Nenhum débito visual: este ticket é do wrapper.

#### Resolution (2026-10-04)
Verdict: Approve

Implementação `5c28b21`; regressões `a71fc99` e `6089387`. Revisão independente
sem bloqueios; teste focado 9/9 e gate completo saída 0 (83 Rust, 61 UI,
95 QA e Android aprovado). QA nativo confirmou três destinos de Lixeira e
hashes; a falha `.pending-*` está coberta de forma controlada, sem reprodução
nativa nesta execução. Falha ambígua continua explícita, com rastreamento
preservado. Evidência: [QA do PR #10](../../qa-runs/2026-10-04-pr10-fixes.md).
