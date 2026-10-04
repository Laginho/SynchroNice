# CLEAN-019: Destino relatado pela limpeza é o destino real (celular e PC)
Stage: done
Status: ready-for-agent
Blocked by: none
Review: agent
Difficulty: hard
Origin: SYN-024, revisão stage 3 de 2026-09-30; item (d) do CLEAN-025

- Primary files:
  - qa/phone.ps1 (`clean`; `Media-Uri` se precisar devolver a URI de Downloads)
  - qa/mediastore.mjs (se o fallback de Downloads passar por ele)
  - qa/phone.test.mjs
  - qa/pc.ps1 (`clean`)
  - qa/pc.test.mjs
  - docs/agents/qa.md (tabela de primitivas, se os rótulos de destino mudarem)

#### What to build

As histórias 9 e 10 da spec pedem que a limpeza mande para a Lixeira quando
der e relate para onde cada arquivo foi. Hoje o relato não confere com o que
acontece, nos dois lados.

**Celular (`phone.ps1 clean`).**
- A query de confirmação passa
  `--extra android:query-arg-match-trashed:i:1` como argv solto. O adb junta
  o argv com espaços, o `content` do aparelho exige `chave:tipo:valor`, e os
  dois-pontos da chave quebram o parser: `[ERROR] Binding not well formed`
  com exit 0. A confirmação nunca vê `is_trashed=1`. O conserto é mandar a
  chave escapada, `'android\:query-arg-match-trashed:i:1'`, dentro de uma
  string única de shell, do jeito que `Media-Uri` já monta o `--where`.
- Quando a confirmação falha, o catch roda `rm` no caminho antigo e relata
  `rm (MediaStore indisponivel)`, mesmo que o update já tenha movido o arquivo
  para `.trashed-*`. O destino relatado passa a ser o que aconteceu:
  - arquivo fora do caminho original depois do update: Lixeira do MediaStore;
  - o `rm` rodou e o arquivo sumiu: rm;
  - o arquivo continua lá depois do `rm`: erro, e o arquivo fica rastreado.
- No AVD de 2026-10-02, um PDF em `Download/SynchroNice` não foi para a
  Lixeira pela URI `content://media/external/file/<id>`, só pela URI de
  Downloads (`content://media/external/downloads/<id>`). Quando o update pela
  URI de arquivo não leva o item à Lixeira, o `clean` tenta a de Downloads
  antes do `rm`. A troca vale só no `clean`: o `share` continua com a URI que
  o `Media-Uri` devolve hoje.

**PC (`pc.ps1 clean`).** `DeleteFile(..., SendToRecycleBin)` apaga de vez
quando o drive não tem Lixeira (um `subst`, por exemplo) ou quando o arquivo
passa da cota dela. Mesmo assim, o relato sempre diz `Lixeira do Windows`.
Depois do delete, o `clean` confere se o arquivo de fato está na Lixeira e
relata `Lixeira do Windows` só nesse caso. Senão relata uma remoção
permanente.

#### Acceptance criteria

1. A query de confirmação do MediaStore chega ao shell do aparelho com a chave
   `android\:query-arg-match-trashed` escapada, numa string única. Um `content`
   falso, executado por sh como no teste 4 do `phone.test.mjs`, recebe
   exatamente três campos: `android:query-arg-match-trashed`, `i` e `1`.
2. Update aceito e confirmação `is_trashed=1`: destino
   `Lixeira do MediaStore`, sem `rm`.
3. Update aceito, mas a confirmação falha (erro do `content` ou resposta sem
   `is_trashed=1`), e o arquivo já saiu do caminho original: destino
   `Lixeira do MediaStore`, sem `rm`.
4. O update pela URI de arquivo não leva o item à Lixeira, e pela URI de
   Downloads leva: destino `Lixeira do MediaStore`, sem `rm`.
5. Nenhuma das URIs leva à Lixeira, e o `rm` remove o arquivo: destino
   `rm (MediaStore indisponivel)`.
6. O arquivo continua no caminho original depois do `rm`: o `clean` sai com
   código 1, e o arquivo continua em `tracked` no manifesto.
7. PC, drive com Lixeira: destino `Lixeira do Windows`, e o arquivo está na
   Lixeira (caso já coberto pelo teste 1 de `pc.test.mjs`, que continua
   passando).
8. PC, drive sem Lixeira (o `subst` do teste 2 de `pc.test.mjs`): o arquivo
   rastreado é apagado, e o destino relatado diz remoção permanente, nunca
   `Lixeira do Windows`.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Tests stage 2 writes (own commit, red)

- Em `qa/phone.test.mjs`, no teste de push/clean: o fake `adb.ps1` responde
  `Row: 0 _id=<n>, _data=<caminho>` à query `--where`, aceita
  `content update` e simula o MediaStore por cenário: Lixeira pela URI de
  arquivo, só pela de Downloads, por nenhuma; confirmação OK, com erro, ou
  arquivo já movido; `rm` que falha em remover. Critérios 2 a 6.
- Também em `qa/phone.test.mjs`: um caso no padrão do teste 4, em que o
  comando de query gerado roda em git-bash com um `content` falso no PATH
  que grava o argv recebido. Critério 1.
- Em `qa/pc.test.mjs`, no teste do drive `subst`: um arquivo rastreado ainda
  presente no `clean`, e o destino relatado conferido. Critério 8.

## Comments

- `qa/phone.ps1:226` passa o extra
  `android:query-arg-match-trashed:i:1` à query de confirmação. O
  [parser AOSP Content.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/cmds/content/src/com/android/commands/content/Content.java)
  separa campos por dois-pontos não escapados e exige três campos:
  chave, tipo e valor. O argumento atual tem quatro; a query não confirma
  `is_trashed=1`. Verificação por leitura da fonte primária, não no Redmi.
- Se o update anterior tiver mandado o arquivo à Lixeira, o catch tenta rm
  no caminho antigo e pode relatar `rm (MediaStore indisponivel)` como
  destino. Esse relato não distingue uma reciclagem já concluída.
- Proposta para triagem: conservar o escape da chave através do PowerShell
  e adb shell, confirmar a query e registrar o destino que ocorreu, com
  um check mínimo no wrapper real. Sem nova dependência.
- Critério 6 de SYN-024 permite Lixeira ou rm; não exige a confirmação ou
  a distinção exata desse caso. Este achado não participa da reabertura.
  Primary files, critérios e seam deste CLEAN precisam passar pelo stage 1.

- Stage 3 de SYN-020 (2026-10-02): confirmado no AVD API 37. A query do
  wrapper com `android:query-arg-match-trashed:i:1` retorna
  `[ERROR] Binding not well formed`, embora o adb saia com código 0.
  `clean` registrou os cinco destinos como `rm (MediaStore indisponivel)`;
  quatro arquivos estavam fisicamente em `.trashed-*`. O PDF ainda estava
  no caminho original, com o mesmo SHA-256 da fixture e `is_trashed=0`,
  depois da retentativa da limpeza. Só esse PDF foi enviado à Lixeira por
  sua URI Downloads observada; uma query com a chave escapada confirmou
  `is_trashed=1` e o caminho `.trashed-*`. Os cinco arquivos ficaram na
  Lixeira. Evidência local em
  `.scratch/qa-runs/2026-10-02-syn-020-review/cleanup-confirmed.txt` e
  relatório `.scratch/qa-runs/2026-10-02-syn-020-review.md`.
  Nenhuma alteração do wrapper ou dos critérios deste CLEAN; nota para
  triagem, sem achado adicional contra SYN-020.

- Triagem 2026-10-03 (/grill-proxy, com o humano):
  > *This was generated by AI during triage.*
  Confirmado no código e no AVD. Bug, ready-for-agent (Q3, proxy). O
  ticket passa a ter três partes: a chave escapada, o destino real e o
  fallback pela URI de Downloads. Absorve o item (d) do CLEAN-025, a
  Lixeira do Windows relatada sempre (Q9, humano). `hard` (Q17, proxy): o
  escape atravessa PS, adb e sh, e são dois lados.

- Stage 2 (2026-10-03): seams exercitadas pelos wrappers `phone.ps1 clean`
  (push real do wrapper, adb falso e query executada por sh) e `pc.ps1 clean`
  (Pasta real e drive subst). Callers examinados: `clean` consome o manifesto
  de `push`/`track`; `Media-Uri` também atende `share`, que permanece intacto.
  Regressões cobertas pelos testes existentes: lista vazia/repetição, arquivos
  alterados ou anteriores e uploads interrompidos. Red: comando focado
  `node --test --test-name-pattern='cleanup reports|PC accepts' qa/phone.test.mjs qa/pc.test.mjs`
  deu 9 testes, 0 passando, 9 falhando (inclui o agrupador dos 7 cenários).
  Falhas: query chega em 8 argumentos; confirmação falha perde o destino real;
  Downloads não é tentado; rm silencioso perde tracked; subst relata Lixeira.
- Stage 2 concluído: query escapada em uma string de shell; confirmação
  alternativa pela ausência após update; fallback Downloads antes de rm;
  rm só encerra o rastreamento quando o caminho sumiu. No PC, uma nova
  entrada na Lixeira, com caminho original e SHA-256 iguais, confirma o
  destino; sem ela, o relato é `remocao permanente`. Documentação atualizada.
  Green focado: 10/10 (inclui o teste existente da Lixeira real do Windows).
  Gate completo em primeiro plano: exit 0, `gate: passou`; Rust 83/83,
  UI 61/61, QA 34/34. Gradle `check` passou; tarefas de teste UP-TO-DATE,
  relatórios existentes com app 94/94 e protocol-client 72/72, sem skips.
  Log local: `.scratch/qa-runs/2026-10-03-clean-019-stage2/gate.log`.
  Diff conferido: apenas Primary files e este ticket; testes separados do
  código. Sem QA no aparelho nesta etapa; stage 3 verifica no emulador.

#### Review (2026-10-03)

Verdict: Reopen — critério 7: atalho reciclado é relatado como remoção permanente.

- Standards: uma violação documental corrigida em 840de1d. Os comentários
  de stage 2 foram movidos ao fim, conforme docs/agents/issue-tracker.md.
  Zero smells acionáveis; Primary files e commits de teste/código separados
  foram respeitados.
- Spec: um achado em qa/pc.ps1:99–102. System.ItemNameDisplay omite .lnk;
  a reconstrução do caminho original rejeita uma entrada real na Lixeira.
  Reprodução fora do sandbox: CLEAN-019-review-identity.lnk foi relatado
  como remocao permanente, mas sua entrada existe na Lixeira com o SHA-256
  do registro anterior de track. System.FileName contém o nome completo;
  ItemNameDisplay contém apenas CLEAN-019-review-identity. O PDF e o .txt
  foram relatados corretamente; o caso subst continuou passando.
- ❌ Critério 7: identificar o nome original completo na confirmação da
  Lixeira e adicionar uma regressão de .lnk em qa/pc.test.mjs, na seam já
  aprovada do wrapper. O teste precisa falhar contra e958f49 e passar com
  a correção, em commits separados. Preservar a confirmação pelos bytes e
  o relato de remoção permanente do critério 8. A correção exige teste novo;
  por isso volta ao stage 2, sem alteração dos critérios ou Primary files.
- ✅ Critério 1: sh recebeu uma string e o extra em três campos; a chave
  escapada foi aceita também pelo content nativo.
- ✅ Critérios 2 e 3: confirmação positiva, erro/exit/resposta negativa após
  mover foram cobertos, sem rm indevido. PNG e PDFs nativos ficaram com
  is_trashed=1 e caminhos .trashed-*.
- ✅ Critério 4: cenário downloads tentou a segunda URI e reciclou sem rm.
  Nos PDFs nativos desta execução, a URI file já reciclou; a precondição
  do fallback não ocorreu. O cenário controlado comprova esse ramo.
- ✅ Critérios 5 e 6: rm removeu somente após ambas as URIs falharem;
  rm silencioso manteve o arquivo e tracked, com exit 1.
- ✅ Critério 8: Windows real/subst apagou a fixture e relatou remoção permanente.
- Callers, falhas e interações examinados: clean com push/track/list-files;
  Media-Uri/mediastore.mjs com share preservado; baseline, aparelho, Pasta
  e hash; upload parcial, ausência inicial, repetição, erros de update/query
  e rm, fallback Downloads e persistência após falha. O PC isolado também
  enviou um PDF recebido pelo app Android. Redmi e outros fluxos visuais
  não foram executados nesta revisão do kit. Nenhuma decisão nova de proxy.
- Prova independente: testes copiados sem alteração e wrappers de 8f06c7e;
  red 9/9 falhando pelas razões esperadas; green focado no Windows 10/10.
  Gate completo em primeiro plano: exit 0, gate: passou; Rust 83/83,
  UI 61/61, QA 34/34. Gradle check passou; testes up-to-date, XMLs existentes
  app 94/94 e protocol-client 72/72, sem falhas, erros ou skips.
- QA e evidências:
  [.scratch/qa-runs/2026-10-03-emulator-CLEAN-019-review.md](../../qa-runs/2026-10-03-emulator-CLEAN-019-review.md).
  up e down concluídos; zero portas do kit escutando ao final; tracked vazio,
  destinos documentados e nenhum arquivo anterior ou não registrado apagado.
  Nenhum débito humano. A limitação da Lixeira no sandbox foi descartada
  por validação no Windows real; o achado .lnk foi reproduzido nesse ambiente.
- Handoff: Stage to-implement na branch clean-019; somente o ❌ acima resta.
  Nenhum merge na sessão e nenhuma entrada CLEAN-019 no ledger.

- Stage 2, correção da reabertura (2026-10-03): seam preservada em
  `pc.ps1 list-files/track/clean`. O teste cria um atalho real via WScript,
  registra e limpa pela interface pública, exige o destino Lixeira e
  preservação do arquivo de destino do atalho. Callers examinados: clean
  consome track/list-files; somente a identificação da entrada reciclada muda.
  Ausência, repetição, baseline, hash alterado e drive subst continuam cobertos.
  Red no Windows real: `node --test qa/pc.test.mjs`, 2 testes, 1 passou e
  1 falhou: o .txt é relatado corretamente, mas o .lnk dá
  `remocao permanente` em vez de `Lixeira do Windows`.
- Reabertura corrigida: `System.FileName` conserva a extensão `.lnk` na
  reconstrução do caminho original; conferência de nova entrada e SHA-256
  preservada. Teste vermelho no commit 614420c, correção sem testes no
  c7618dc. Green focado: 2/2, incluindo o caso subst e preservação do alvo
  do atalho. Gate completo em primeiro plano: exit 0, `gate: passou`;
  Rust 83/83, UI 61/61, QA 34/34, formatação, Clippy e typecheck passaram.
  Android `check` passou (testes UP-TO-DATE). Diff final limitado aos dois
  Primary files do PC e ao ticket, sem alteração dos critérios ou do celular.
  Stage 2 encerra em to-review; re-review independente ainda pendente.

#### Resolution (2026-10-03)

Verdict: Approve

- Re-review limitada ao ❌ do critério 7 e ao diff desde 2c3bb37.
  Standards: zero violações documentadas e zero smells acionáveis.
  Spec: zero achados; nenhum requisito parcial ou ampliação de escopo.
  As duas revisões foram realizadas por subagentes independentes.
- ✅ Critério 7: `System.FileName` conserva `.lnk` e a confirmação mantém
  entrada nova, caminho original completo e SHA-256. O teste usa um atalho
  Windows real, chama track/clean e preserva seu alvo. Prova nativa na Pasta
  isolada encontrou `.txt` e `.lnk` na Lixeira com os hashes registrados;
  `ItemNameDisplay` omitiu `.lnk`, mas `FileName` devolveu o nome completo.
- ✅ Critério 8 preservado: o teste subst apagou a fixture e relatou
  `remocao permanente`, tanto no red quanto no green. Critérios 1–6
  mantidos: todos os sete cenários de limpeza do celular passaram no gate;
  a evidência nativa da primeira revisão permanece válida para esses ramos
  sem alterações. Nenhum critério ou Primary file foi renegociado.
- Callers e falhas examinados nesta passagem: list-files/baseline, track,
  clean, pc.mjs, guardas de Pasta/manifesto/hash/reparse point, ausência,
  repetição, registro de cleanup e falha de exclusão. Android e limite de
  cota da Lixeira não foram reexecutados nativamente; o código desses ramos
  não mudou nesta correção. Nenhuma decisão nova de proxy e nenhum débito
  humano: todos os passos novos exigidos pela reabertura foram verificados.
- Red independente: testes atuais copiados sem alterações, wrapper de
  produção de 614420c; 2 testes, 1 passou e 1 falhou no destino do `.lnk`,
  exit 1. Green na branch corrigida: 2/2, exit 0, sem falhas ou skips.
  Teste/código separados em 614420c/c7618dc; nenhum teste foi alterado pela
  revisão. Repetir clean manteve os caminhos e destinos, com tracked vazio.
- Gate completo em primeiro plano após rebase na sessão: exit 0,
  `gate: passou`. Rust 83/83, UI 61/61, QA 34/34; formatação, Clippy e
  typecheck passaram. Gradle check: BUILD SUCCESSFUL, testes UP-TO-DATE;
  XMLs conferidos com app 94/94 e protocol-client 72/72, zero falhas,
  erros ou skips. Log local: `2026-10-03-clean-019-rereview/gate.log`.
- QA e destinos: [relatório da re-review](../../qa-runs/2026-10-03-emulator-CLEAN-019-rereview.md).
  up compilou a branch, pareou e confirmou presença; down passou, com
  active=false, zero portas do kit escutando e zero arquivos rastreados.
  Nenhum arquivo anterior ou não registrado foi apagado. O build terminou
  sem interrupção efetiva; a retentativa do SDK usou o caminho configurado
  no projeto. A formatação do Cargo.toml reescrita pelo build foi restaurada.
- Arquivos da correção: qa/pc.ps1 e qa/pc.test.mjs; evidências nos dois
  relatórios de QA, neste ticket e no ledger. Diff final conferido, sem
  alterações alheias ao ticket, segredos ou artefatos gerados committed.
  Merge sem squash 5081871 na sessão sweatshop/2026-10-03-1608; Stage done
  e ledger registrados no mesmo commit de encerramento. Sem PR ou push
  nesta etapa; o merge do PR de sessão permanece com o humano.
