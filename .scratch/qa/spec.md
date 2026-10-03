# Spec: Kit de QA dirigido por agente

Status: ready-for-agent

Fonte: grilling de 2026-09-30 (4 rodadas), a partir do QA manual do mesmo dia
sobre `docs/relatorios/2026-09-27-walkthrough-qa.tex`. Vocabulário:
`CONTEXT.md`. Regra do "Humano simulado": `AGENTS.md`.

## Problem Statement

O walkthrough de QA tem 54 passos e leva cerca de 1h30 à mão. No QA manual de
2026-09-30, o humano percebeu que boa parte deles (digitar, tocar, ler um
status, conferir um arquivo na Pasta) poderia ter sido feita por um agente
antes dele. Hoje nenhum agente consegue: o app Android nunca foi dirigido por
um agente (o Computer Use foi negado no `qemu`), o PC só foi visto pela
janela, e o que não se viu virou `Débito humano:` nos tickets, somado até o
humano pagar tudo de uma vez.

No loop sem humano, o revisor do stage 3 e o `proxy` aprovam critérios
visíveis sem ver o app rodando. A evidência deles é o teste de unidade, que
não pega uma bolha que não aparece ou um botão que sumiu.

## Solution

Um kit de primitivas que dirige os dois aparelhos, mais instruções para um
agente usá-lo. O agente lê o roteiro (o walkthrough, ou os critérios de um
ticket), executa cada passo pelas primitivas, julga o resultado pelo que elas
devolvem e escreve um relatório com um veredito por passo: ✅ passou, ❌ falhou
(com evidência) ou 👤 humano (o kit não alcança). O roteiro continua em prosa.
Nenhum passo vira código.

Dois alvos de celular, com o mesmo kit:

- **Emulador** (`SynchroNice_API37`), sem janela, para o loop: o revisor do
  stage 3 e o `proxy` usam como evidência dos critérios visíveis de um
  ticket. O PC roda numa instância isolada, sem mexer no Par real do humano.
- **Redmi físico**, pelo adb sem fio, contra o `pc.exe` real do humano: no
  pós-run do foreman (critérios visíveis dos tickets do run, mais os passos ★
  como smoke), no walkthrough completo e no `/audit` pré-release.

O humano fica só com o que o kit não alcança, e sabe exatamente o que é.

## User Stories

### Humano
1. Como humano, quero que um agente percorra o walkthrough antes de mim, para eu só fazer os passos que um agente não consegue fazer.
2. Como humano, quero um relatório com um veredito por passo, para saber o que passou, o que falhou e o que sobrou para mim.
3. Como humano, quero que cada ❌ traga evidência em texto (trecho do dump, resposta do `/listen`, arquivo na Pasta), para confiar no veredito sem refazer o passo.
4. Como humano, quero que cada falha do walkthrough vire um `BUG-*` `needs-triage`, sem `Stage:`, para triar do mesmo jeito que triei os achados do QA manual.
5. Como humano, quero que o kit nunca despareie meu Redmi do meu PC quando roda no emulador, para eu não descobrir depois que o app parou de sincronizar.
6. Como humano, quero que o kit, ao terminar no emulador, deixe minha instância do PC aberta como estava, para o app continuar funcionando sem eu lembrar de reabrir.
7. Como humano, quero que o kit nunca aponte o autostart do Windows para um build de debug, para o PC continuar subindo o executável certo.
8. Como humano, quero que tudo que o kit criar no Redmi e no PC seja apagado no fim, e só isso, para meu aparelho não acumular fotos de teste.
9. Como humano, quero que o que o kit apagar vá para a Lixeira (no PC sempre, no celular quando der), para nada ser irreversível.
10. Como humano, quero a lista do que foi apagado, e para onde foi, no relatório, para conferir se quiser.
11. Como humano, quero parear o adb sem fio uma vez e o kit reconectar sozinho, para não precisar de cabo.
12. Como humano, quero que a queda do adb seja relatada como falha de infraestrutura, e não como bug do app, para não triar um falso `BUG-*`.
13. Como humano, quero ver os prints localmente, sem que eles pesem no git, para olhar quando um veredito me deixar em dúvida.

### Agente revisor (stage 3) e `proxy`
14. Como revisor, quero subir emulador, APK e PC de debug da branch do ticket com um comando só, que não refaz o que já está de pé, para verificar o ticket sem gastar o turno em preparação.
15. Como revisor, quero tocar num elemento pelo seu `testTag`, para o passo não quebrar quando o texto da UI mudar.
16. Como revisor, quero digitar texto no celular e no PC, para mandar um Item nos dois sentidos.
17. Como revisor, quero ler a árvore da tela do celular (textos, tags, descrições), para julgar se a bolha, o status ou o botão estão lá.
18. Como revisor, quero um print de cada aparelho, para o que a árvore não mostra (miniatura, cor).
19. Como revisor, quero clicar, digitar e ler o DOM da janela do PC, para os passos do lado do PC.
20. Como revisor, quero chamar os comandos Tauri do PC (`list_items` e afins) pelo CDP, para ler o estado sem depender da tela.
21. Como revisor, quero saber se o PC vê o celular presente (`HEAD /listen`), para os passos de "Na rede".
22. Como revisor, quero listar os arquivos da Pasta do PC e de `Download/SynchroNice` no celular, para os passos de entrega.
23. Como revisor, quero empurrar uma foto, um PDF, um vídeo e um arquivo grande para o celular e compartilhá-los com o SynchroNice como se viessem da galeria, para os passos da seção D.
24. Como revisor, quero parear o celular com o PC sem câmera, a partir do JSON do QR, para os passos de Desparear e parear de novo.
25. Como revisor, quero montar o clipboard do Windows (imagem, arquivos) e disparar Ctrl+V na janela do PC, para os passos de colar.
26. Como revisor, quero desligar o Wi-Fi do celular por alguns segundos sem perder o canal de volta, para o passo de queda de rede.
27. Como revisor, quero ver a saída de erro do PC (o `eprintln!`) quando fui eu que o abri, para diagnosticar um ❌.
28. Como revisor, quero que um passo instável tente de novo uma vez antes do ❌, para não reprovar um ticket por um dump que pegou a tela no meio da animação.
29. Como revisor, quero que uma falha do kit num critério visível reprove a revisão do próprio ticket, como qualquer critério não atendido, para não abrir `BUG-*` sobre trabalho que ainda está em revisão.
30. Como `proxy`, quero tentar o kit antes de registrar `Débito humano:`, para registrar só o que ele de fato não alcança.
31. Como `proxy`, quero não registrar nada quando o kit verificou o passo, e só apontar o relatório, para a lista de débito não crescer com coisa já verificada.

### Foreman
32. Como foreman, quero rodar os critérios visíveis dos tickets do run, mais os passos ★, no Redmi no fim do run, para o PR de sessão chegar ao humano já verificado no aparelho real.
33. Como foreman, quero seguir com "sem aparelho" no resumo quando o Redmi não aparece no `adb devices`, para o run não parar por isso.
34. Como foreman, quero derrubar o emulador no fim do run, para não deixar um `qemu` consumindo a máquina.

### Quem escreve tickets
35. Como autor de ticket, quero que todo elemento de UI tocado por um critério tenha `testTag`, para o kit alcançá-lo.

## Implementation Decisions

- **Forma.** Primitivas pequenas, cada uma atrás de um wrapper PowerShell,
  numa pasta própria na raiz (`qa/`). O agente decide a ordem e o veredito. O
  `--allowedTools` do driver libera `powershell:*` e `node:*` (conferido no
  `sweatshop.ps1`), e o Codex roda sem sandbox. O kit cabe nos dois sem mudar
  o driver. Instruções de uso num documento em `docs/agents/`, referenciado
  pelos bindings do `AGENTS.md`, e não numa skill: o Codex também roda o
  fluxo e não lê skills do Claude.
- **Primitivas do celular:** `adb -s <serial>`, com o serial por argumento ou
  variável. Tocar num elemento (por `testTag`, texto ou descrição), digitar,
  apertar tecla, dump da árvore, print, listar e empurrar arquivos, abrir e
  matar o app, compartilhar um arquivo, parear pelo JSON e desligar o Wi-Fi
  por N segundos.
  - Tocar resolve o elemento no XML do `uiautomator dump`: acha o nó e devolve
    o centro dos bounds. Esse parser é a única lógica com ramificação do kit.
  - Antes de uma sessão, o kit zera as três escalas de animação. O dump é
    repetido quando falha por "could not get idle state".
  - **Compartilhar** empurra o arquivo, dispara a varredura de mídia, obtém a
    URI do MediaStore e faz `am start` de `SEND` na `ShareActivity`, com
    `--grant-read-uri-permission` e a URI em `-d` e em `EXTRA_STREAM`.
    `SEND_MULTIPLE` fica de fora: o `am` não monta lista de URIs.
  - **Wi-Fi fora** roda no próprio aparelho, em `nohup`: desliga, espera e
    religa. O canal do adb volta sozinho e o kit reconecta.
- **Primitivas do PC:** `playwright-core` (`connectOverCDP`, sem baixar
  navegador), num `package.json` próprio do kit. Um script ESM fora de `pc/`
  não enxerga `pc/node_modules`.
  - Clicar, digitar e ler o DOM, tirar print e chamar
    `window.__TAURI__.core.invoke`.
  - Também: `HEAD /listen` com o token do `config.json`, listar a Pasta e
    montar o clipboard do Windows (imagem ou lista de arquivos) antes de
    disparar o paste pelo CDP.
  - O PC é aberto com
    `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=--remote-debugging-port=<porta>`.
    O WebView2 soma esse valor aos argumentos do wry, não os substitui.
  - Quando é o kit que abre o PC, a saída de erro vai para um arquivo da
    execução.
- **Isolamento do PC no emulador.**
  - O app ganha uma variável de ambiente, `SYNCHRONICE_DATA_DIR`. Com ela
    definida:
    - toda a configuração (Par, Pasta, outbox, `accepted-file-ids.txt`) vive
      nesse diretório;
    - o registro no autostart não acontece.
  - Uma função pura resolve o diretório e diz se o autostart vale. Os dois
    lugares que hoje pedem o `app_config_dir()` passam por ela.
  - O kit define também `WEBVIEW2_USER_DATA_FOLDER`, que o próprio WebView2
    respeita, para não dividir o perfil com a instância real.
  - A porta continua 47820 fixa. O kit mata a instância real antes e a reabre
    no fim.
  - A Pasta isolada é semeada no `config.json` isolado, dentro do próprio
    diretório.
- **Redmi usa a instância real.** O `release\pc.exe`, com os dados e o Par
  reais: só ele tem a regra do firewall, e o Redmi já está pareado com ele.
  Os passos de Desparear trocam o token. O atalho de pareamento reata o mesmo
  aparelho.
- **Ganchos no app Android.**
  - `testTagsAsResourceId` na raiz do Compose e `testTag` nos elementos que
    o roteiro toca: composer, enviar, câmera, bolhas (com o estado),
    indicador de presença, engrenagem e cada item do painel.
  - Uma activity só de debug, exportada só no manifest de debug, recebe o JSON
    do QR num extra de string. Ela chama o `acceptQr` do `AppState` da
    `Application`, abre o chat e termina. Ela vale nos dois alvos. No Redmi,
    só a primeira leitura (B.2) continua pela câmera real, e é ela que prova
    o scanner.
- **Emulador.**
  - Sobe sem janela: `-no-window -no-audio -no-snapshot-save
    -gpu swiftshader_indirect`, com `-gpu` fixo para as execuções saírem
    iguais.
  - A câmera de trás vem de `-camera-back imagefile:<png do QR>`. Um spike
    decide se a CameraX lê o QR assim. Se ler, o B.2 passa a ser verificável
    no emulador. Se não, o B.2 fica 👤 no emulador e o pareamento vai pelo
    atalho.
  - A rede é o NAT do host: o app alcança o IP de LAN do PC direto, que é o
    IP que o QR leva (BUG-002). Não há mDNS, e o IP do QR cobre isso.
- **Preparação idempotente** (`up`): sobe o emulador se ele não estiver de pé
  e espera o `boot_completed`. Depois compila e instala o APK de debug da
  branch atual e compila o PC de debug (`npx tauri build --debug
  --no-bundle`). Por fim abre o PC isolado com CDP. Quem chama é o próprio
  stage 3 ou o `proxy`, porque o build precisa ser o da branch do ticket. O
  foreman derruba o emulador no fim do run.
- **Relatório.**
  - Formato: um arquivo por execução, em `.scratch/qa-runs/<data>-<alvo>.md`.
  - Cada passo recebe ✅, ❌ ou 👤, com evidência em texto.
  - Os prints ficam num subdiretório da execução, fora do git (gitignore).
  - A lista do que foi apagado, e para onde, fica no fim do relatório.
- **O destino do ❌.**
  - No walkthrough, vira `BUG-*` `needs-triage` sem `Stage:`, apontando o
    passo e o ticket de origem.
  - Por ticket, reprova a revisão do próprio ticket.
  - Queda do adb que não volta com `adb connect`, ou emulador que não sobe, é
    falha de infraestrutura: aborta a execução, não vira `BUG-*` e não
    reprova ticket.
- **Débito.** Quando o kit verifica um passo, não se registra débito. O
  comentário da revisão aponta o relatório. `Débito humano:` fica só para o
  👤.
- **Limpeza.**
  - O kit rastreia por nome o que empurrou e o que a execução fez chegar.
  - No PC, apaga para a Lixeira (`FileSystem.DeleteFile` com
    `SendToRecycleBin`).
  - No celular, marca `is_trashed` no MediaStore quando der, e apaga com
    `rm` quando não der.
  - Nunca toca em nada que o kit não criou.
- **Retentativa.** Um passo instável tenta de novo uma vez antes do ❌.
- **Integração.**
  - Uma linha nos bindings do `AGENTS.md` aponta o documento do kit. O stage 3
    usa o kit como evidência dos critérios visíveis. O `proxy` tenta o kit
    antes de registrar débito.
  - O foreman roda o pós-run no Redmi.
  - Nada muda na `ticket-flow`, no `proxy.md` nem no driver, que são globais.
  - A regra "elemento de UI tocado por critério ganha `testTag`" entra no
    documento do kit.
- **Continua 👤 no v1:**
  - no Redmi, o B.2 (câmera real lendo o QR na tela);
  - a bandeja (A.3, A.4) e as notificações;
  - o drag nativo para o Explorer, o navegador ou um site (G.1–G.3);
  - o drag do Explorer para a janela (F.1, e a primeira parte do G.7);
  - o reinício do Windows (J.3) e derrubar a rede do PC (B.5);
  - o compartilhamento múltiplo (D.4, D.5);
  - a RAM no Gerenciador de Tarefas (A.6);
  - o toque longo e a pinça de zoom, quando o `input` não bastar.

  O Computer Use do Codex fica para depois, se a lista 👤 sair longa.

## Testing Decisions

- Um bom teste aqui prova comportamento externo: dado um ambiente, o app faz
  ou não faz. Nada sobre como o kit está organizado por dentro.
- **Resolução do diretório do PC:** teste de unidade da função pura. Com a
  variável, usa o caminho dela e desliga o autostart. Sem ela, usa o padrão
  e permite o autostart, que a engrenagem liga (SYN-030). Arte anterior: `pc/src-tauri/tests/config.rs`.
- **Atalho de pareamento:** teste Robolectric no source set de teste de
  debug. Depois do intent com o JSON, o Par existe e o `last-address` tem o IP
  do QR. Arte anterior: `ShareActivityTest` (activity dirigida por intent) e
  `AcceptQrIpTest`.
- **Parser do dump:** `node --test` sobre XML real capturado do app. Ele acha
  por resource-id, texto e descrição, calcula o centro dos bounds e dá
  "não achado" quando o nó não existe. Entra no `gate.ps1` ao lado do
  `copiaveis.test.ts`.
- **Sem teste de gate:** os `testTag`s e o driver CDP. A prova deles é o kit
  rodando, e fica no `#### Verification` dos tickets. O repo não tem
  `compose-ui-test` nem Playwright, e adicioná-los só para provar metadados é
  peso sem ganho.

## Out of Scope

- Suíte de regressão estruturada (passos como código ou como dados). O
  roteiro continua em prosa.
- Manter o walkthrough `.tex` em dia a cada ticket. Ele fica congelado. O
  pós-run usa os critérios dos tickets.
- Computer Use para bandeja, drag nativo e notificações.
- `SEND_MULTIPLE` pelo kit (um teste instrumentado ou toques no app Arquivos
  seriam os caminhos, se um dia valer).
- USB como canal do adb.
- Porta configurável do PC (a instância isolada e a real nunca rodam juntas).
- Mudanças na `ticket-flow`, no `proxy.md`, no `sweatshop` ou no `foreman`
  globais.
- Verificação pelo stage 2: o TDD dele são os testes de unidade.

## Further Notes

- O `pc.exe` não tem instância única: uma segunda cópia falha ao abrir a
  porta 47820 e divide o perfil do WebView2. O kit sempre mata a cópia em
  execução antes de abrir a sua.
- A varredura do `am start SEND` a partir do shell não foi executada de ponta
  a ponta: o shell lê o MediaStore no Redmi, e a regra da AOSP permite
  conceder o que se lê. O ticket do celular confirma.
- O AVD já teve `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (assinatura diferente da
  instalada). O `up` desinstala e instala de novo nesse caso.
- O `adjustResize` da a3f6618 ainda não foi verificado no aparelho e entra no
  walkthrough.
