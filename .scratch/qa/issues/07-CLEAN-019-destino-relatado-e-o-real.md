# CLEAN-019: Destino relatado pela limpeza é o destino real (celular e PC)
Stage: to-review
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
