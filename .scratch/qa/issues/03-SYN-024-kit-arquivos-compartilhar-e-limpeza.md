# SYN-024: Kit de QA — arquivos, Compartilhar, Wi-Fi fora e limpeza
Stage: implementing
Status: ready-for-agent
Blocked by: SYN-022
Review: agent

- Primary files:
  - qa/phone.ps1 (empurrar, compartilhar, listar, Wi-Fi fora, limpeza)
  - qa/pc.ps1, qa/pc.mjs (clipboard e colar, listar a Pasta, limpeza)
  - New: qa/mediastore.mjs, qa/mediastore.test.mjs (saída do `content query`)
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

#### Verification

    powershell -ExecutionPolicy Bypass -File .\qa\phone.ps1 share <foto>
    powershell -ExecutionPolicy Bypass -File .\qa\phone.ps1 wifi-off 15
    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Sem o Redmi alcançável, os critérios 3–8 viram `Débito humano:`.

## Tests stage 2 writes (own commit, red)

- `qa/mediastore.test.mjs`: várias linhas, caminho ausente, `_data` com
  vírgula ou espaço. Vermelho porque `qa/mediastore.mjs` não existe.

## Comments

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
