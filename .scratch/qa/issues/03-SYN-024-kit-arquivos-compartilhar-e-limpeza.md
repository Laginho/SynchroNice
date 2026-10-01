# SYN-024: Kit de QA — arquivos, Compartilhar, Wi-Fi fora e limpeza
Stage: to-implement
Status: ready-for-agent
Blocked by: SYN-022
Review: agent

- Primary files:
  - qa/phone.ps1 (empurrar, compartilhar, listar, Wi-Fi fora, limpeza)
  - qa/pc.ps1, qa/pc.mjs (clipboard e colar, listar a Pasta, limpeza)
  - New: qa/mediastore.mjs, qa/mediastore.test.mjs (saída do `content query`)
  - New: qa/phone.test.mjs (adb falso temporário, preparação opcional versus ação pedida)
  - New: qa/pc.test.mjs (wrapper real PowerShell 5.1, Pasta/configuração temporárias, proteção da limpeza)
  - gate.ps1 (só a linha do `node --test` do kit)
  - android/app/src/main/java/synchronice/android/MainActivity.kt (só `testTag` na engrenagem, nos itens do painel e na câmera)
  - android/app/src/main/java/synchronice/android/ImageViewer.kt (só `testTag`, `testTagsAsResourceId` na raiz do Dialog e imports/opt-in necessários)
  - docs/agents/qa.md (seções das primitivas novas)

#### What to build

Spec: `.scratch/qa/spec.md`, histórias 8–10, 22, 23, 25, 26, e a seção
"Implementation Decisions" (Primitivas do celular, Primitivas do PC,
Limpeza).

O kit passa a mover arquivos e a limpar o que criou.

- **Celular.**
  - Empurrar um arquivo do PC para `Pictures/SynchroNiceQA/`.
  - Compartilhar um arquivo com o SynchroNice como se viesse da galeria:
    1. varredura de mídia;
    2. `_id` pelo `content query`;
    3. URI do MediaStore;
    4. `am start` de `SEND` na `ShareActivity`, com
       `--grant-read-uri-permission` e a URI em `-d` e em `EXTRA_STREAM`.
  - Listar `Download/SynchroNice`.
  - Desligar o Wi-Fi por N segundos: roda em `nohup` no próprio aparelho,
    que desliga, espera e religa. Depois, o kit reconecta o adb.
- **PC.**
  - Listar a Pasta.
  - Montar o clipboard do Windows com uma imagem, ou com uma lista de
    arquivos, e disparar o paste pelo CDP.
- **Limpeza.**
  - O kit rastreia por nome o que empurrou e o que a execução fez chegar.
  - No PC, manda para a Lixeira (`FileSystem.DeleteFile` com
    `SendToRecycleBin`).
  - No celular, marca `is_trashed` no MediaStore quando der, e apaga com
    `rm` quando não der.
  - Nunca toca em nada fora da lista.
  - O relatório lista cada arquivo e o destino dele.
- **App.** `testTag` na engrenagem, em cada item do painel, no botão de câmera
  e no visualizador de imagem.

#### Acceptance criteria

1. Dada a saída do `content query --projection _id:_data`, o parser acha o
   `_id` do caminho pedido entre várias linhas e monta a URI do MediaStore.
   Com o caminho ausente, dá erro "não achado". Um `_data` com vírgula ou com
   espaço não quebra o parser.
2. O `node --test` do parser roda no `gate.ps1` e passa.
3. Compartilhar uma foto pelo kit põe a foto na Pasta do PC e cria uma bolha
   no chat do PC (passo D.1). Se o Android 16 recusar a concessão da URI a
   partir do shell, o ticket vai para `blocked`, com a saída do `am` como
   evidência.
4. O Wi-Fi fora por N segundos derruba a presença (`HEAD /listen` devolve
   204) e a religa depois. O kit volta a alcançar o aparelho sem intervenção.
5. Ctrl+V com uma imagem no clipboard, e depois com um e com dois arquivos
   copiados, cria uma bolha à direita no PC para cada um. Cada um chega em
   `Download/SynchroNice` (passo G.7, sem a parte do drag).
6. A limpeza manda os arquivos do PC para a Lixeira e os do celular para a
   lixeira do MediaStore, ou apaga com `rm`. Um arquivo que não está na lista
   do kit continua no lugar.
7. No dump, os resource-ids da engrenagem, dos itens do painel, da câmera e do
   visualizador aparecem.
8. Um relatório do kit rodando D.1 e G.7 no Redmi, com a lista da limpeza,
   está committed em `.scratch/qa-runs/`.
9. Na preparação opcional (escalas de animação, `KEYCODE_WAKEUP`,
   `wm dismiss-keyguard`), só `SecurityException` vira aviso no stderr e a
   ação segue. Erro de transporte, bloqueio seguro e falha da ação pedida
   (inclusive `INJECT_EVENTS` em `key`) continuam fatais. `qa/phone.test.mjs`
   prova isso no wrapper real via `-Adb`/`-Serial` e roda no gate.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\qa\phone.ps1 share <foto>
    powershell -ExecutionPolicy Bypass -File .\qa\phone.ps1 wifi-off 15
    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Sem o Redmi alcançável, os critérios 3–8 viram `Débito humano:`.

## Tests stage 2 writes (own commit, red)

- `qa/mediastore.test.mjs`: várias linhas, caminho ausente, `_data` com
  vírgula ou espaço. Vermelho porque `qa/mediastore.mjs` não existe.
- `qa/phone.test.mjs`: negativa de preparação seguida de dump (vermelho
  porque o wrapper aborta); ação negada, bloqueio seguro e falha de transporte
  continuam fatais. Fake adb só no limite externo, em diretório temporário.
- `qa/pc.test.mjs`: arquivo anterior à fotografia inicial é recusado;
  novo rastreado vai à Lixeira; fora da lista e alterado ficam preservados;
  manifesto antigo malformado não permite apagar. Vermelho: PowerShell 5.1
  serializa o baseline como objeto `{value, Count}`, permitindo `track` anterior.

## Comments

- 2026-09-30, stage 2 concluído: `95a46e1` é o commit vermelho do parser,
  `5d65455` o da preparação, `ced5962` o da limpeza PC. Implementações
  `44c8161`, `6ad0599` e `50f3d44` não alteram arquivos de teste. Vermelhos
  reais: módulo ausente; abortar em preparação opcional negada; aceitar
  arquivo anterior. Verdes: 3 testes MediaStore, 1 teste do wrapper Android
  com quatro cenários e 1 teste do wrapper PC com proteções da limpeza.
- 2026-09-30: gate completo em primeiro plano, código 0, `gate: passou`,
  `BUILD SUCCESSFUL in 2m 25s`. Rust 51, UI 47, QA 10, Android app 59,
  protocol-client 62 (`UP-TO-DATE`, XMLs confirmados): 229 testes, zero
  falhas, erros ou skips. fmt, clippy, typecheck e lint passaram.
  Sintaxe PowerShell/ESM e `git diff --check` também passaram.
- Relatório: `.scratch/qa-runs/2026-09-30-024-redmi.md`. Clipboard real de
  imagem/um/dois arquivos produziu quatro bolhas à direita, verificadas por
  CDP e Computer Use. Limpeza PC enviou só os arquivos explicitamente
  registrados à Lixeira; anteriores, fora da lista e alterados são
  protegidos. Quatro Pendentes criados pelo QA foram descartados por id/nome
  após enviar seus payloads à Lixeira, sem alegar entrega ao Redmi.
- Débito humano: SYN-024 critério 3, D.1 completo — após a preparação
  opcional corrigida, `share` aborta com `infraestrutura: aparelho bloqueado`;
  não chegou ao push nem ao `am`. Nenhuma recusa de concessão foi observada.
- Débito humano: SYN-024 critério 4 — `wifi-off 15` aborta pelo bloqueio
  seguro antes do nohup; transição de presença e reconexão ainda não vistas.
- Débito humano: SYN-024 critério 5, chegada dos quatro arquivos ao Redmi —
  lado PC comprovado; `list-files` no celular aborta pelo bloqueio seguro.
- Débito humano: SYN-024 critério 6, limpeza no MediaStore/rm do Redmi —
  nenhum arquivo foi criado no aparelho; operação inacessível pelo bloqueio.
- Débito humano: SYN-024 critério 7, resource-ids no dump real — `dump`
  aborta pelo bloqueio seguro. Computer Use tentou a verificação visual:
  viu as bolhas do PC, mas não há janela de espelhamento do Redmi.
  Não houve alteração de PIN, permissões de segurança, Par ou Wi-Fi.

- Proxy decided: acrescentar `qa/pc.test.mjs` aos Primary files e à linha do
  gate, testando `list-files`/`track`/`clean` reais com `powershell.exe` 5.1,
  `-Config` e `-RunDir` temporários — regressão da proteção já exigida pelo
  critério 6. Atribuir JSON a uma variável antes de `@()` e validar o baseline
  ao carregar o manifesto protege tanto registro quanto limpeza. Manifestos
  antigos malformados são recusados; arquivos temporários de teste usam
  prefixo `synchronice-qa-test-` para identificar a pequena cópia na Lixeira.
  Decisão read-only via Claude CLI, agente `proxy`, `opus xhigh`.
- 2026-09-30, verificação: `track` aceitou uma foto anterior pela conversão
  de array no PowerShell 5.1. A execução foi interrompida antes de `clean`;
  o registro inseguro foi retirado do manifesto local e a foto continua
  intacta. O conserto será precedido do teste vermelho acima.

- Proxy decided: acrescentar `qa/phone.test.mjs` e critério 9 para testar o
  wrapper real com adb falso através dos parâmetros existentes `-Adb` e
  `-Serial` — a preparação tolerante cria uma decisão warn/fatal que precisa
  de regressão; sem novo parâmetro ou acesso ao aparelho real nos testes.
  Decisão read-only via Claude CLI, agente `proxy`, modelo `opus`, esforço
  `xhigh`. A etapa 2 em andamento continua em `implementing` até o gate.

- Proxy decided: expor `testTagsAsResourceId` na raiz do Dialog de
  `ImageViewer`, com imports e opt-in necessários; tornar preparação opcional
  de animações/wakeup tolerante apenas a `SecurityException`, sem mudar a
  segurança do Redmi, e tentar D.1/G.7 antes de registrar débito — o Dialog
  tem raiz própria e a preparação atual aborta ações que não precisam dessas
  permissões. Demais falhas adb, ações solicitadas e bloqueio seguro continuam
  fatais; negativa de concessão no `am` continua `blocked`.
  Decisão obtida do agente `proxy` via Claude CLI, `--agent proxy --model opus
  --effort xhigh --tools Read`, sem escrita do proxy no repo.

- 2026-09-30, stage 2: branch `syn-024`, base `sweatshop/2026-09-30-2146`.
  Seam aprovada: `mediaUri(output, path)` em `qa/mediastore.mjs`, chamada pelo
  wrapper para compartilhar e limpar. Testes cobrem seleção exata entre linhas,
  vírgula/espaço, saída vazia, id inválido e caminho ausente. Vermelho real:
  `node --test qa/mediastore.test.mjs`, 1 arquivo falhou com
  `ERR_MODULE_NOT_FOUND`, antes de existir implementação.
  Callers lidos: wrappers nos exemplos de `docs/agents/qa.md`; `pc.mjs`
  recebe somente o JSON de `pc.ps1`; `ImageViewer` é aberto por `ChatScreen`.
  As mudanças Android serão apenas tags. Lista vazia e colisão com arquivo
  existente precisam ser seguras na limpeza; os arquivos recebidos serão
  registrados explicitamente após uma fotografia inicial da Pasta, sem
  incluir automaticamente outros arquivos que aparecerem durante o QA.

#### Review stage 3 (2026-09-30)

Reaberto: quatro falhas confirmadas exigem novos testes, portanto voltam à
etapa 2 pela regra mecânica do `ticket-flow`. Nenhuma correção de fonte foi
feita na revisão. Branch `syn-024`; base `sweatshop/2026-09-30-2146`
(`5f02f14`); diff inteiro até `fc384a2` e callers lidos numa única passada.
Relatório: `.scratch/qa-runs/2026-09-30-024-review.md`.

- ❌ Critério 6 — proteção de arquivo anterior no PC: em PowerShell 5.1
  com `[Console]::OutputEncoding` 850, `list-files` registra `prévia.txt`
  como `pr├®via.txt`; `track` aceita o arquivo anterior real, com hash e
  caminho válidos para a limpeza. `qa/pc.ps1:45–51` define somente
  `$OutputEncoding`, que não controla a decodificação do stdout do Node.
  Reproduzido em Pasta temporária, sem chamar `clean` sobre esse arquivo.
  O teste exigido de recusa de arquivo anterior precisa cobrir esse caso.
- ❌ Critérios 4 e 9 — Wi-Fi: `qa/phone.ps1:184–208` ignora erro do
  lançamento de `nohup` e aceita apenas `get-state=device` como sucesso.
  Com adb falso, lançamento negado por `SecurityException: CHANGE_WIFI_STATE`
  sai 0 e devolve `reconnected=true`, com a negativa apenas em `log`.
  Falha da ação pedida deve continuar fatal; não se demonstrou queda real.
- ❌ Critério 6 — upload interrompido: `qa/phone.ps1:155–161` reserva e
  registra o arquivo com `hash=null` antes do push. Depois de uma falha de
  transporte, `clean` em `:221` recusa permanentemente o arquivo criado
  pelo kit. O adb falso reproduziu push com código 1 e, após reconexão,
  limpeza com código 1, `Arquivo sem hash ou alterado; preservado`.
  A recuperação deve preservar as proteções de conteúdo e arquivos alheios.
- ❌ Critério 6 — Pasta na raiz: `qa/pc.ps1:32` transforma `C:\` em
  `C:`; os guards de `track`/`clean` em `:58`/`:70` não aceitam o diretório
  real `C:\`. O app permite essa Pasta; a desigualdade foi reproduzida
  sem criar arquivos na raiz. Preservar a raiz ao normalizar o caminho.

- Standards: escopo e separação test-only/código aprovados; 0 smells
  relevantes. O achado Wi-Fi também viola a regra documentada de ação
  pedida fatal. Os oito commits anteriores não têm corpo explicando o
  motivo, exigido pelo `ticket-flow`; observação histórica não bloqueante.
- Spec: quatro falhas acima, sem scope creep. As três decisões
  `Proxy decided` foram revisadas: teste real PC/baseline; adb falso e
  critério 9; semântica da raiz do Dialog e preparação opcional tolerante
  apenas a `SecurityException`. Todas estão dentro do contrato expandido.
- Achado adicional sem critério de confirmação/relato exato do destino:
  escape do extra de query do MediaStore, registrado em CLEAN-019 para
  triagem. Não faz parte dos ❌ desta retomada. Pasta inicialmente vazia
  passou em uma verificação independente; nenhum achado nesse caso.
- Prova red-green original reproduzida em diretórios temporários: parser
  de `95a46e1` sem módulo falha `ERR_MODULE_NOT_FOUND`; teste de `5d65455`
  com wrapper `44c8161` falha por preparação negada; teste de `ced5962`
  com wrappers `6ad0599` falha por aceitar arquivo anterior (0 em vez de 1).
  A dependência já instalada foi copiada para o harness PC; nenhuma cópia
  da lógica testada foi criada. Cada execução vermelha: 1 teste falhou.
- Gate completo em primeiro plano: código 0, `gate: passou`,
  `BUILD SUCCESSFUL in 9s`. Rust 51, UI 47, QA 10, Android app 59,
  protocol-client 62: 229 testes; XMLs Android com zero falhas, erros e
  skips. fmt, clippy, typecheck e lint passaram. O gate atual não cobre
  as quatro falhas novas. `git diff --check` passou.
- Redmi atualmente só por USB, com transporte sem fio offline; USB não
  foi usado. Os débitos já registrados permanecem. Nenhuma nova operação
  de Wi-Fi, Par ou arquivo real do aparelho. Sem merge nem linha de ledger;
  a retomada na branch existente cobre somente os quatro ❌ acima.
