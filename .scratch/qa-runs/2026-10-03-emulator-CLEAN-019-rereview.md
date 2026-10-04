# QA — 2026-10-03 — CLEAN-019 — re-review

Verdict: Approve

Escopo: somente o critério 7 reaberto e o diff desde 2c3bb37 até 6d55f16. Correção de produção: c7618dc. Base da sessão: 4cd3e06, sweatshop/2026-10-03-1608.
Evidências locais: `2026-10-03-clean-019-rereview/` (ignorado pelo git).

## Standards

Zero violações documentadas e zero smells acionáveis. A correção troca uma propriedade na identificação do nome completo, mantém as condições anteriores e explica a omissão de extensões no nome de exibição. O novo teste é proporcional ao bug e usa `list-files`, `track` e `clean` reais.

Escopo e separação de commits respeitados: 614420c contém teste e ticket; c7618dc altera somente `qa/pc.ps1`; 6d55f16 registra o handoff. Os critérios permanecem intactos e os comentários foram acrescentados ao fim. A revisão Standards foi realizada por subagente independente, sem alterações ou execução de testes.

## Spec

Zero achados no diff e no ❌ anterior. O critério 7 está atendido: `System.FileName` conserva `.lnk`, e a confirmação ainda exige uma entrada nova na Lixeira, o caminho original completo e os bytes registrados. O alvo do atalho é preservado. O caso sem Lixeira continua exigindo remoção permanente, conforme o critério 8.

A revisão Spec foi realizada por subagente independente. Foram examinados `list-files` → baseline → `track` → `clean`, a listagem em `pc.mjs`, Pasta, manifesto, hash, reparse points, ausência, repetição, falha de exclusão e persistência de cleanup. Nenhum achado adicional, requisito novo ou decisão de proxy.

## Critérios e prova independente

| Critério | Veredito | Evidência |
| --- | --- | --- |
| 1–6 — limpeza Android | ✅ mantidos | Os sete cenários atuais do wrapper passaram no gate: confirmed, query-error, query-exit, query-empty, downloads, rm e rm-fails. Nenhuma alteração nesses ramos desde a [primeira revisão](2026-10-03-emulator-CLEAN-019-review.md), que conserva a evidência nativa. |
| 7 — Lixeira Windows, inclusive `.lnk` | ✅ | Teste novo falhou com o wrapper de 614420c: `.txt` correto, `.lnk` relatado como remoção permanente. Com c7618dc, os dois testes do PC passaram. A execução nativa independente confirmou `.txt` e `.lnk` na Lixeira, com SHA-256 igual ao salvo antes de clean e ausência no caminho original. |
| 8 — drive `subst` sem Lixeira | ✅ | O mesmo teste de drive temporário passou no red e no green: fixture ausente e destino `remocao permanente`. |

Red: testes atuais copiados sem alterações, executados com o wrapper de produção anterior à correção (614420c). SHA-256 do teste original e da cópia: `837513814B277F68FEF415D3146D6346BFF6B96427F6EDA6D385C86CD94407F9`. Resultado: 2 testes, 1 passou, 1 falhou exatamente no relato de `.lnk`; exit 1. Green no Windows real: 2 testes, 2 passaram, 0 falhas, skips ou cancelamentos; exit 0. Logs: `red.log` e `green.log`.

Na prova nativa, `System.FileName` devolveu `CLEAN-019-rereview-native.lnk`, enquanto `System.ItemNameDisplay` devolveu `CLEAN-019-rereview-native`. O arquivo reciclado tem SHA-256 `D6226F6A29B8C42E28E87834424CCC2BAFFD39417F245E3B4E7739119B75C7E1`, igual ao registro anterior de track. O alvo local do atalho foi preservado. `tracked` ficou vazio e a repetição de clean manteve os mesmos caminhos e destinos.

Uma comparação textual/array no script auxiliar da revisão gerou falso negativo na checagem extra de repetição. A leitura foi corrigida no auxiliar local e a comparação campo a campo passou; nenhum teste ou código de produção foi modificado. Evidências: `pc-tracked-before.json`, `pc-cleanup.json`, `pc-repeat-cleanup.json`, `pc-recycle-evidence.json`, `native-pc-resume.log`.

## Infraestrutura e encerramento

`qa/up.ps1` compilou a branch do ticket, instalou o APK, abriu o PC isolado, pareou e confirmou `online=true`. O primeiro up procurou o SDK no caminho padrão inexistente; a execução com `-Sdk D:\AppData\Local\Android\Sdk`, conforme `android/local.properties`, passou. O build do PC levou 10m36s e terminou antes de qualquer tentativa de interrupção efetiva; nenhum compilador foi encerrado.

`qa/down.ps1` com o mesmo RunDir e SDK passou: `stopped=true`, `restored=false`, estado `active=false`. O PC real não estava aberto antes do up. Consulta final confirmou zero portas do kit escutando (47820, 9222, 5554, 5555) e zero arquivos rastreados; evidência em `ports-after-down.json` e `pc-files.json`.

Nesta re-review, o Android não recebeu arquivos novos; seus critérios e as evidências anteriores foram preservados. Redmi e limite de cota da Lixeira não foram reexecutados. Nenhum débito humano: todos os passos novos exigidos pela reabertura foram verificados no Windows real.

## Destinos da limpeza nativa

| Caminho criado na Pasta isolada desta execução | Destino confirmado |
| --- | --- |
| `D:\Desktop\Projects\SynchroNice\.scratch\qa-runs\2026-10-03-clean-019-rereview\data\Pasta\CLEAN-019-rereview-native.txt` | Lixeira do Windows; SHA-256 `D8AF1A08F9EE452D4E7A677DE7EE7DE1A1F1E7B68251F1B80CDCE1ED3D294F7B` |
| `D:\Desktop\Projects\SynchroNice\.scratch\qa-runs\2026-10-03-clean-019-rereview\data\Pasta\CLEAN-019-rereview-native.lnk` | Lixeira do Windows; SHA-256 igual ao registro anterior de track |

Os caminhos completos e os caminhos físicos das entradas recicladas ficam nos JSONs de evidência. Fixtures de teste automatizado também foram removidas de suas Pastas temporárias; o teste `subst` confirmou remoção permanente. Nenhum arquivo anterior ou não registrado foi apagado. O alvo do atalho, fonte local no diretório de evidências, permanece preservado.

## Gate

Branch rebaseada sobre a sessão antes da validação, sem conflitos. `powershell -ExecutionPolicy Bypass -File .\gate.ps1` rodou em primeiro plano e terminou com exit 0 e `gate: passou`. Formatação, Clippy e typecheck passaram. Rust: 83/83; UI: 61/61; QA: 34/34, sem falhas, skips ou cancelamentos. Gradle `check`: BUILD SUCCESSFUL; testes UP-TO-DATE. XMLs existentes conferidos: app 94/94 e protocol-client 72/72, zero falhas, erros ou skips. Log: `2026-10-03-clean-019-rereview/gate.log`.

Standards: 0 achados; Spec: 0 achados. O único ❌ da revisão anterior está resolvido.
