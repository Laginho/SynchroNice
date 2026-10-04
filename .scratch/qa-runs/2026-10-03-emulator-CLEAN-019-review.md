# QA — 2026-10-03 — CLEAN-019 — stage 3

Verdict: Reopen — critério 7: atalho reciclado é relatado como remoção permanente.

Branch: clean-019. Entrega revisada: e958f49; base fixa: 4cd3e06, da sessão sweatshop/2026-10-03-1608. Correção documental da revisão: 840de1d.
Evidências locais: 2026-10-03-clean-019-review/ (ignorado pelo git).

## Standards

Uma violação documental: os comentários de stage 2 precediam as notas antigas, contrariando o append exigido por docs/agents/issue-tracker.md. Corrigida em 840de1d, preservando o texto e a cronologia. Zero smells acionáveis.

Escopo e test-first corretos: 8f06c7e contém testes e ticket; e958f49 contém implementação, documentação e ticket, sem alterar testes. Os arquivos de produção estão nos Primary files.

## Spec

Um achado no critério 7: “PC, drive com Lixeira: destino Lixeira do Windows, e o arquivo está na Lixeira”.

qa/pc.ps1:99–102 reconstrói o caminho original com [System.ItemNameDisplay](https://learn.microsoft.com/en-us/windows/win32/properties/props-system-itemnamedisplay), o nome de exibição. Para um atalho, essa propriedade omite .lnk. A entrada legítima não corresponde ao caminho rastreado e o wrapper relata remocao permanente.

Reprodução nativa fora do sandbox, em drive com Lixeira: CLEAN-019-review-identity.lnk recebeu esse relato, embora sua entrada exista na Lixeira, fora do caminho original, com SHA-256 idêntico ao salvo por track. ItemNameDisplay = CLEAN-019-review-identity; System.FileName = CLEAN-019-review-identity.lnk. Evidência: native-pc/identity-tracked-before.json, identity-cleanup.json e identity-recycle-evidence.json. O PDF da mesma Pasta foi relatado corretamente.

É necessária uma correção da identificação do nome original e um teste de regressão na seam já aprovada de qa/pc.test.mjs. O stage 3 reabre porque a correção precisa de novo teste. Nenhuma outra ausência, implementação incorreta ou ampliação de escopo identificada. Nenhuma decisão nova de proxy.

## Critérios

| Critério | Veredito | Evidência |
| --- | --- | --- |
| 1 — extra escapado em uma string | ✅ | Teste confirmed: um argumento de shell e três campos no content falso executado por sh. Adb nativo aceitou a mesma chave escapada nas queries de confirmação, sem Binding not well formed. |
| 2 — confirmação positiva, sem rm | ✅ | confirmed passou; PNG e PDFs no emulador receberam Lixeira do MediaStore, com is_trashed=1 e caminhos .trashed-* observados. |
| 3 — confirmação falha após mover | ✅ | query-error, query-exit e query-empty passaram; destino Lixeira, sem rm e com o arquivo movido. |
| 4 — fallback Downloads | ✅ | Cenário downloads: URI file não move; URI downloads move, sem rm. Nesta execução nativa, tanto o PDF varrido como o recebido pelo app aceitaram a URI file; a precondição do fallback não ocorreu. Sua cobertura é o cenário controlado do wrapper. |
| 5 — nenhuma URI recicla, rm remove | ✅ | Cenário rm passou, tentou Downloads antes do rm e confirmou a ausência. |
| 6 — rm não remove | ✅ | rm-fails saiu 1; arquivo preservado, tracked com um registro e cleanup vazio. |
| 7 — Lixeira real do Windows | ❌ | Teste existente de .txt e PDF nativo passaram. Dois .lnk nativos chegaram à Lixeira mas foram relatados como remoção permanente. A reprodução final também conferiu o hash anterior. |
| 8 — subst sem Lixeira | ✅ | Teste PC accepts passou no Windows real: arquivo ausente, destino remocao permanente. |

Callers e interações examinados: clean consumindo manifestos de push/track/list-files; Media-Uri/mediastore.mjs e seu caller share, preservado; baseline, aparelho e Pasta; hash divergente, upload parcial, ausência inicial e repetição; erros/exit/respostas negativas de update/query, fallback e falha silenciosa de rm; persistência dos manifestos após falha. O envio real pelo PC isolado confirmou também um PDF recebido pelo app Android. Redmi e os demais fluxos visuais do app não foram executados nesta revisão do kit.

## Prova red-green e gate

As cópias de phone.test.mjs e pc.test.mjs usadas na prova têm os mesmos SHA-256 dos testes entregues. Os wrappers antigos vieram de 8f06c7e, antes da correção.

- Red: cleanup reports e PC accepts, **9 testes, 0 passaram, 9 falharam**, pelas razões do contrato: oito argumentos de shell, destino incorreto, falta de Downloads, perda de tracked após rm silencioso e relato falso no subst. Log: proof/red.log.
- Green focado no ambiente Windows: incluindo PC cleanup, **10 testes passaram, zero falhas**. Log: proof/green-host.log.
- Gate completo em primeiro plano: exit 0, gate: passou. Rust **83**, PC/UI **61**, QA **34**, todos sem falhas; fmt, clippy e typecheck passaram.
- Gradle check: BUILD SUCCESSFUL in 32s, 41 tasks (1 executada, 40 up-to-date). Testes Android ficaram up-to-date; os XMLs conferidos somam app **94** e protocol-client **72**, sem falhas, erros ou skips. Log: gate.log.

A primeira execução focada restrita deu 9/10: a enumeração da Lixeira no sandbox não via entradas e o caso .txt relatou remoção permanente. O mesmo teste passou no ambiente Windows. Mais tarde, o executor restrito falhou com CreateProcessWithLogonW 1909; as leituras e registros restantes foram concluídos com acesso ao ambiente. Essas limitações não são achados contra o ticket.

## QA nativo e encerramento

up compilou/instalou o APK da branch e compilou o PC debug em 7m 10s, com dados isolados; endpoint 127.0.0.1:5555, presença confirmada. A query real observou:

- PNG, URI file/41: is_trashed=1, caminho .trashed-1793660154-CLEAN-019-f492eb35.png.
- PDF varrido, URI file/42: is_trashed=1, caminho .trashed-1793660157-CLEAN-019-f492eb35.pdf.
- PDF recebido pelo app, URI file/43: confirmação pela URI downloads/43, is_trashed=1 e SHA-256 6653fecba18d4970500defef6042c9be5c838fc22b6c6d533739cc853b0c8ccb, igual à fonte.

Os probes de PDF reciclaram pela URI file; somente essas fixtures foram restauradas antes do clean para medir o wrapper com o arquivo presente. Os scripts locais tiveram duas correções na interpretação de arrays JSON do PowerShell; a conferência retomou os arquivos já criados, sem reenviar o Item. Logs finais: native-phone-verify.log e native-downloads-resume.log; dados: phone-media-after.json e app-received-media-after.json.

Destinos retornados pelos wrappers:

| path | destination relatado | Destino observado |
| --- | --- | --- |
| /storage/emulated/0/Pictures/SynchroNiceQA/CLEAN-019-f492eb35.png | Lixeira do MediaStore | Lixeira do MediaStore |
| /storage/emulated/0/Download/SynchroNice/CLEAN-019-f492eb35.pdf | Lixeira do MediaStore | Lixeira do MediaStore |
| /storage/emulated/0/Download/SynchroNice/CLEAN-019-app-e73bbd9f.pdf | Lixeira do MediaStore | Lixeira do MediaStore |
| D:\Desktop\Projects\SynchroNice\.scratch\qa-runs\2026-10-03-clean-019-review\native-pc\Pasta\CLEAN-019-review.pdf | Lixeira do Windows | Lixeira do Windows |
| D:\Desktop\Projects\SynchroNice\.scratch\qa-runs\2026-10-03-clean-019-review\native-pc\Pasta\CLEAN-019-review.lnk | remocao permanente | Lixeira do Windows |
| D:\Desktop\Projects\SynchroNice\.scratch\qa-runs\2026-10-03-clean-019-review\native-pc\Pasta\CLEAN-019-review-identity.lnk | remocao permanente | Lixeira do Windows |

tracked ficou vazio nos dois manifestos; clean repetido no celular conservou os resultados. Arquivos anteriores ou não registrados permaneceram preservados. O harness de testes removeu seus temporários em finally; o teste de subst usa remoção permanente nas fixtures conforme o critério 8.

down terminou com stopped=true, restored=false; nenhuma instância release estava aberta antes do QA. Nenhuma porta 5554/5555/47820/9222 continuou escutando. A normalização de linhas de Cargo.toml pelo Tauri foi restaurada após conferir blob idêntico ao HEAD. Dados e fontes de QA permanecem no diretório local ignorado. Nenhum débito humano.

Standards: 1 achado documental corrigido, zero pendentes. Spec: 1 achado, critério 7 incorreto; reabertura.