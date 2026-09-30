# SYN-022: Kit de QA — o agente manda texto nos dois sentidos no Redmi
Stage: to-implement
Status: ready-for-agent
Blocked by: none
Review: agent

- Primary files:
  - New: qa/phone.ps1 (primitivas do celular)
  - New: qa/pc.ps1, qa/pc.mjs (primitivas do PC)
  - New: qa/dump.mjs, qa/dump.test.mjs, qa/fixtures/ (parser do `uiautomator dump`)
  - New: qa/package.json, qa/package-lock.json (só `playwright-core`)
  - New: docs/agents/qa.md
  - gate.ps1 (só a linha do `node --test` do kit)
  - .gitignore (`qa/node_modules/`, prints de `.scratch/qa-runs/`)
  - android/app/src/main/java/synchronice/android/MainActivity.kt (só `testTagsAsResourceId` na raiz e as tags deste ticket)

#### What to build

Spec: `.scratch/qa/spec.md`, histórias 2, 3, 11–13, 15–21, 27, 28, e a
seção "Implementation Decisions" (Forma, Primitivas do celular, Primitivas do
PC, Relatório).

O fio condutor do kit, de ponta a ponta. Um agente, só com `powershell` e
`node`, abre o `release\pc.exe` com a porta de CDP, alcança o Redmi pelo adb
sem fio, manda um texto do PC para o celular e outro do celular para o PC, e
julga os dois pelo que as primitivas devolvem. O resultado é o primeiro
relatório em `.scratch/qa-runs/`.

- **Celular.**
  - Só o transporte sem fio do adb. O kit acha o aparelho pelo `adb mdns
    services`, ou recebe `ip:porta` ou serial por argumento ou variável, e
    faz `adb connect`. Um aparelho visto só pelo USB conta como "sem
    aparelho".
  - Primitivas:
    - tocar num elemento por `testTag`, texto ou descrição;
    - digitar e apertar uma tecla;
    - dump da árvore;
    - print;
    - abrir e matar o app.
  - Antes da primeira ação, o kit zera as três escalas de animação.
  - Quando o dump falha por "could not get idle state", o kit tenta de novo.
- **PC.**
  - Abrir: mata o `pc.exe` em execução e abre o `release\pc.exe` com
    `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=--remote-debugging-port=<porta>`,
    com a saída de erro redirecionada para um arquivo da execução.
  - Primitivas:
    - clicar e digitar por seletor, e ler o texto do DOM;
    - print da janela;
    - chamar um comando Tauri (`window.__TAURI__.core.invoke`);
    - presença: `HEAD /listen` com o token do `config.json`, 200 ou 204.
- **App.**
  - `testTagsAsResourceId` na raiz do Compose.
  - `testTag` no composer, no enviar, em cada bolha e no indicador de
    presença.
- **Documento.** `docs/agents/qa.md`, primeira versão:
  - pré-requisito: o `adb pair` feito uma vez pelo humano;
  - cada primitiva, com um exemplo;
  - o formato do relatório;
  - a regra de tentar de novo uma vez;
  - a regra de falha de infraestrutura;
  - a regra de que todo elemento de UI tocado por um critério ganha
    `testTag`.

#### Acceptance criteria

1. Dado o XML de um `uiautomator dump`, o parser acha um nó por resource-id,
   por texto e por descrição, e devolve o centro dos bounds
   (`[x1,y1][x2,y2]`). Para um nó que não existe, devolve "não achado" em vez
   de uma coordenada.
2. O `node --test` do parser roda no `gate.ps1` e passa.
3. O kit do celular recusa o transporte USB. Com o Redmi só no cabo, ele
   relata "sem aparelho". Com o adb sem fio pareado, ele conecta sozinho, sem
   porta digitada.
4. No dump da tela do chat, os resource-ids do composer, do enviar, das bolhas
   e da presença aparecem.
5. O kit do PC abre o `release\pc.exe` com CDP, depois de matar a cópia em
   execução. Pelo CDP, ele lê o texto de uma bolha e recebe o resultado de
   `list_items`. O `HEAD /listen` devolve 200 com o Redmi no chat.
6. `docs/agents/qa.md` cobre tudo o que está listado em "Documento" acima.
7. Um relatório do kit rodando os passos C.1 e C.2 do walkthrough no Redmi,
   com um veredito e evidência em texto por passo, está committed em
   `.scratch/qa-runs/`. Os prints ficam fora do git.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\qa\phone.ps1 dump
    powershell -ExecutionPolicy Bypass -File .\qa\pc.ps1 start
    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Sem o Redmi alcançável pelo adb sem fio, os critérios 3–5 e 7 viram
`Débito humano:`. Os critérios 1, 2 e 6 não dependem do aparelho.

## Tests stage 2 writes (own commit, red)

- `qa/dump.test.mjs`: busca por resource-id, texto e descrição, centro dos
  bounds e "não achado", sobre um XML de fixture no formato do `uiautomator
  dump`, de preferência capturado do app. Vermelho porque `qa/dump.mjs` não
  existe.

## Comments

- 2026-09-30, stage 1: `adb pair` feito pelo humano. O Redmi responde em
  `192.168.18.9:<porta>` e aparece no `adb mdns services` como
  `adb-njca85bihiau99t8-*._adb-tls-connect._tcp`. O adb conecta sozinho pelo
  nome mDNS, então o mesmo aparelho pode aparecer duas vezes no `adb devices`
  (pelo IP e pelo nome mDNS). O kit escolhe um transporte e usa sempre `-s`.
  A porta muda quando a Depuração sem fio é religada.
- 2026-09-30, stage 1: antes de tocar no aparelho, o kit acende a tela
  (`input keyevent KEYCODE_WAKEUP`) e tira a tela de bloqueio
  (`wm dismiss-keyguard`). Se o bloqueio for seguro (PIN), o kit não
  desbloqueia: relata "aparelho bloqueado" como falha de infraestrutura. O kit
  nunca recebe nem digita o PIN. O humano deixa o Redmi no carregador com
  "Permanecer ativo" durante o run.
