# Kit de QA

O agente lê o roteiro em prosa, chama as primitivas e julga cada passo.
Instale a única dependência com `npm ci --prefix qa`; não baixa navegador.
O PC usa o WebView2 instalado, via [CDP do Playwright](https://playwright.dev/docs/api/class-browsertype#browser-type-connect-over-cdp).

## Quem usa o kit e quando

Estas regras são locais ao repo; as skills globais e o driver continuam
inalterados. O roteiro permanece em prosa, com relatório e evidência por passo
conforme "Execução, retentativa e relatório" abaixo.

### Stage 3 e `proxy`: emulador

O stage 3 roda `qa/up.ps1` na branch do ticket e usa o emulador com PC isolado
para verificar todos os critérios visíveis que o kit alcança. O relatório
aponta o ID e o número de cada critério; a revisão aponta esse relatório.
Um ❌ reprova a revisão do próprio ticket, que volta a `to-implement` com
os critérios não atendidos, sem abrir `BUG-*` sobre esse trabalho.
Falha de infraestrutura aborta a execução e é registrada como tal; não
reprova o ticket nem vira bug do app.

O `proxy` também tenta o kit no emulador antes de registrar `Débito humano:`.
Passo verificado pelo kit só aponta o relatório no comentário do ticket,
sem débito. Para o que o kit não alcança (👤), tente o Computer Use do Codex
conforme `AGENTS.md`; registre débito apenas do que continuar sem verificar.

O último usuário do emulador roda `qa/down.ps1` com o mesmo `-RunDir` do `up`;
numa revisão avulsa, rode ao terminar. Num run, o emulador pode ser reutilizado
pelos próximos tickets; cada revisão chama `up` para compilar sua branch.

### Foreman: pós-run no Redmi

No fim do run, confira `adb devices`. Se o Redmi estiver conectado como
`device` por transporte sem fio, rode nele os critérios visíveis dos tickets
do run e os passos ★ (`\mvp`) de
`docs/relatorios/2026-09-27-walkthrough-qa.tex`, contra o PC release real.
Antes de passar ao Redmi, rode `qa/down.ps1` para liberar a porta 47820 e
restaurar o PC real; siga os pré-requisitos e as primitivas abaixo.

Inclua o relatório no resumo do run. Cada ❌ do pós-run abre `BUG-*` com
`Status: needs-triage`, sem `Stage:`, citando o passo ou critério, o ticket
de origem e a evidência. Falha de infraestrutura é registrada no relatório
e no resumo, sem reprovar tickets nem abrir bugs do app. Se o Redmi não
aparecer conectado sem fio, escreva "sem aparelho" no resumo e siga o run.
Passos 👤 seguem a regra de débito acima.

Limpe somente os arquivos rastreados pelo kit e inclua os destinos no
relatório. Ao encerrar o run, inclusive após falha de infraestrutura ou
"sem aparelho", rode `qa/down.ps1` com o `-RunDir` usado no emulador;
o comando pode ser repetido. Ele também devolve o PC real ao normal depois
do pós-run no Redmi: relança o exe registrado sem CDP, isolamento ou
redirecionamento de saída, mesmo sem emulador ativo.

## Emulador: PC isolado

Prepare os SDKs, dependências do PC e `npm ci --prefix qa`. Rode o gate uma
vez para que o ZXing do projeto Android esteja no cache do Gradle; o kit o
reutiliza para gerar o PNG do QR, sem instalar outro gerador. O AVD esperado
é `SynchroNice_API37`, no SDK em `%LOCALAPPDATA%/Android/Sdk` (`-Sdk` muda
esse caminho nos dois wrappers).

```powershell
powershell -ExecutionPolicy Bypass -File .\qa\up.ps1
powershell -ExecutionPolicy Bypass -File .\qa\pc.ps1 presence -Isolated -RunDir .scratch/qa-runs/emulator
powershell -ExecutionPolicy Bypass -File .\qa\phone.ps1 dump -Serial '127.0.0.1:5555'
powershell -ExecutionPolicy Bypass -File .\qa\down.ps1
```

`up` reutiliza o AVD que já está de pé e aguarda `sys.boot_completed`. Ao
subi-lo, usa `-no-window -no-audio -no-snapshot-save -gpu swiftshader_indirect`
e `-camera-back imagefile:<PNG do QR>`. Compila/instala o APK debug da branch
atual; assinatura incompatível desinstala **só o app no emulador** e reinstala.
Concede a permissão de notificações no emulador, compila o PC com
`npx tauri build --debug --no-bundle`, anota/fecha o release real e abre o
debug isolado com CDP. Pareia pelo atalho do SYN-023 com o IP de LAN e só
termina após `HEAD /listen` confirmar presença. Repetir `up` recompila a
branch e reabre o PC debug, preservando o processo do emulador e a informação
de que o release real deve ser restaurado. Erro chama `down` antes de sair.

O diretório padrão da execução é `.scratch/qa-runs/emulator`; `-RunDir`
escolhe outro e deve ser repetido no `down` e em **todas** as ações do PC.
Use `pc.ps1 -Isolated -RunDir <execução>` também em `type`, `invoke`,
`presence`, `list-files` e limpeza. `data/config.json`, `data/outbox`,
`data/accepted-file-ids.txt` e a Pasta `data/Pasta` ficam aí. O app respeita
`SYNCHRONICE_DATA_DIR` e não registra autostart nesse modo; o kit também
define `WEBVIEW2_USER_DATA_FOLDER` como `webview` dentro da execução. Os
logs do PC ficam em `pc.stderr.log` e `pc.stdout.log`.

A porta HTTP continua 47820: use uma execução por vez. O CDP usa 9222
(`up -Port` e `pc.ps1 -Port` podem mudá-la). O adb do emulador também está
disponível em loopback: o padrão é `127.0.0.1:5555`; o JSON final do `up`
informa `endpoint` quando reutiliza um AVD em outra porta. Passe esse
endpoint explicitamente em `phone.ps1 -Serial`; a descoberta automática
continua escolhendo só celulares sem fio. No `pair`, passe ainda
`-Config <execução>/data/config.json`. USB continua recusado.

`down` encerra o AVD e o PC debug desta árvore. Reabre `release/pc.exe`
somente se ele estava aberto antes do primeiro `up`. Pode ser repetido;
preserva os dados e evidências locais. O estado fica em `emulator.json`.
O lançamento usa [Win32_ProcessStartup](https://learn.microsoft.com/en-us/windows/win32/cimwin32prov/win32-processstartup)
com janela oculta para não herdar os pipes de uma sessão de agente.

**Spike da câmera (2026-10-01): B.2 permanece 👤 no emulador.** Com o app
limpo e CAMERA concedida, a CameraX exibiu o `imagefile:`, mas cortou o QR
na horizontal. Mesmo após reduzir o código e acrescentar margem branca,
duas leituras mantiveram a tela do scanner e `HEAD /listen` 204. Não houve
pareamento pela câmera. Use o atalho `pair` para os demais passos; o B.2
continua sendo a leitura real do QR no Redmi. Evidências locais do SYN-025:
`.scratch/qa-runs/syn-025/scanner.png`, `scanner-padded.png` e os dumps
`camera-padded.xml`/`camera-padded-retry.xml`.

## Redmi: pré-requisitos

O humano faz `adb pair ip:porta` uma vez, na Depuração sem fio do Android.
Deixe celular e PC no mesmo Wi-Fi, tela desbloqueada e celular no carregador,
com Permanecer ativo. O kit acorda a tela e pede `wm dismiss-keyguard`, mas
nunca recebe ou digita PIN. Bloqueio seguro é falha de infraestrutura.
USB é recusado, inclusive quando é o único transporte em `adb devices`.

`phone.ps1` procura um transporte sem fio conectado ou o serviço
`_adb-tls-connect._tcp` de `adb mdns services`, faz `adb connect` e usa sempre
`-s`. Não escolhe serviços de pareamento nem emuladores. Para escolher um
aparelho, passe `-Serial '192.168.18.9:37123'` ou o serial mDNS completo;
`SYNCHRONICE_ADB_SERIAL` também vale. A porta pode mudar: prefira descoberta.
`-Adb` ou `SYNCHRONICE_ADB` indica outro adb; o padrão usa PATH ou o SDK em
`%LOCALAPPDATA%/Android/Sdk/platform-tools/adb.exe`.

Na preparação opcional (escalas de animação, wakeup, dismiss-keyguard),
`SecurityException` gera aviso no stderr e o kit segue. O kit não altera
configurações de segurança para contornar a negativa. Erros de transporte,
bloqueio seguro e falha da ação pedida continuam fatais, inclusive
`INJECT_EVENTS` negado num toque ou tecla. Um dump ainda tenta de novo
quando não consegue idle state.

Antes das ações, o wrapper zera as três escalas de animação. Um dump com
`could not get idle state` tenta de novo uma vez. Os arquivos temporários
de dump e captura criados pelo kit são removidos depois da leitura.

## Primitivas do celular

Execute da raiz do repo (prefixo de todos os exemplos:
`powershell -ExecutionPolicy Bypass -File .\qa\phone.ps1`):

| Ação | Exemplo de argumentos | Resultado |
| --- | --- | --- |
| Abrir app | `open` | resposta de `am start` |
| Matar app | `stop` | `am force-stop`; encerra a Sessão |
| Tocar por tag | `tap -By id -Value composer` | centro dos bounds no dump; erro se não achado |
| Tocar por texto | `tap -By text -Value Enviar` | busca exata, com entidades XML decodificadas |
| Tocar por descrição | `tap -By description -Value 'Configurações'` | busca exata de `content-desc` |
| Digitar | `type -Text 'QA celular para PC'` | digita no campo em foco |
| Tecla | `key -Key KEYCODE_BACK` | esconde o teclado, por exemplo |
| Árvore | `dump` | XML com textos, descrições, resource-ids e bounds |
| Print | `screenshot -Path '.scratch/qa-runs/2026-09-30-redmi/phone.png'` | PNG local; crie o diretório antes |
| Parear sem câmera | `pair` ou `pair -Ip '192.168.18.12'` | lê o Par do PC, dispara a activity de debug e abre o chat |
| Listar recebidos | `list-files -RunDir '.scratch/qa-runs/2026-09-30-024-redmi'` | caminhos em `Download/SynchroNice`; primeira chamada registra os arquivos anteriores ao QA |
| Empurrar arquivo | `push -Path 'qa-foto.png' -RunDir '<execução>'` | grava em `Pictures/SynchroNiceQA/`, recusa nome existente e registra para limpeza |
| Compartilhar | `share -Path 'qa-foto.png' -RunDir '<execução>'` | empurra, varre mídia, resolve URI e dispara SEND na ShareActivity |
| Wi-Fi fora | `wifi-off 15` | desliga por 15 segundos e reconecta adb; retorna só depois da reconexão |
| Registrar chegada | `track -Path '/storage/emulated/0/Download/SynchroNice/imagem-123.png' -RunDir '<execução>'` | registra somente o arquivo novo identificado pelo agente como resultado do QA |
| Limpar | `clean -RunDir '<execução>'` | JSON de cada arquivo e destino; MediaStore ou `rm` |

`share` recebe um arquivo **local do PC**, inclusive na posição 1. Não precisa
chamar `push` antes. Usa `content call --method scan_file`, depois
`content query --projection _id:_data` e `am start -a android.intent.action.SEND`
com `--grant-read-uri-permission`, URI em `-d` e extra URI `--eu
android.intent.extra.STREAM`. A sintaxe vem do [Intent do AOSP](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/java/android/content/Intent.java)
e do [comando content](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/cmds/content/src/com/android/commands/content/Content.java).
`-Mime` sobrescreve o MIME inferido pela extensão; desconhecidas usam
`application/octet-stream`. SEND_MULTIPLE continua manual. Se o Android 16
recusar a concessão no `am`, guarde a saída integral e bloqueie SYN-024.

`wifi-off` aceita 1–600 segundos (`-Seconds` também vale). Um script `nohup`
no aparelho usa um trap para religar o Wi-Fi, mesmo sem adb. O script
consulta `dumpsys wifi` até dez vezes, com intervalo de um
segundo, para confirmar cada estado antes de emitir os marcadores de
desligamento/restauração. Os N segundos começam só em `disabled`, depois
de terminar `disabling`; a restauração exige `enabled`.
Código 0 do `svc` sozinho não confirma a ação;
recusa silenciosa, consulta negada ou estado não confirmado geram falha.
A linha `Wi-Fi is enabled/disabled` vem do
[ActiveModeWarden do Android 16](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/android16-release/service/java/com/android/server/wifi/ActiveModeWarden.java#1572).
`cmd wifi status` não serve como confirmação: também imprime `disabled`
durante `disabling`, quando a rede ainda pode estar ativa.
Depois de reconectar o adb, aguarda até 20 segundos pelos dois marcadores;
o transporte pode voltar antes de o trap terminar de confirmar a restauração.
Enquanto a chamada aguarda, outra chamada a `pc.ps1 presence` permite observar 204;
depois da reconexão, espere 200. A descoberta mDNS atualiza a porta quando
o IP foi informado. Não interprete apenas `reconnected=true` como prova da
presença do app: confira `/listen` antes, durante e depois. Falha de
reconexão é infraestrutura. Não desligue o Wi-Fi sem um aparelho acessível.

`pair` só funciona com o **APK de debug**: `DebugPairActivity` e sua declaração
exportada existem apenas no source set `debug`, nunca no release. O extra
string `qr` recebe o JSON completo e passa pelo mesmo `acceptQr` do scanner;
JSON inválido ou ausente apenas encerra a activity, sem criar Par.
O wrapper lê `nome_instancia` (emitido como `instance_name` no QR) e `token` de
`%APPDATA%/dev.synchronice.pc/config.json` (`-Config` troca esse caminho),
usa a porta 47820 e o IPv4 escolhido pela rota padrão do PC, como o QR real.
`-Ip` sobrescreve o IPv4, por exemplo quando há VPN. O token não deve ir para
relatórios. O pareamento substitui o Par anterior do celular.
No Redmi, use depois de Desparear pela engrenagem (I.2–I.4); B.2 continua
pela câmera real. Confira o dump com presença `Na rede` e `pc.ps1 presence`
com status 200. Falta de aparelho ou negativa das permissões do shell é
falha de infraestrutura; o atalho não contorna os pré-requisitos do kit.

`adb input text` aceita aqui ASCII imprimível sem `%`. Texto Unicode não é
suportado por essa primitiva e gera erro explícito. Espaços e caracteres de
shell são protegidos; o campo deve estar em foco antes de digitar.

Todo elemento de UI tocado por um critério deve ganhar `testTag`.
A raiz Compose expõe tags como resource-ids com
[`testTagsAsResourceId`](https://developer.android.com/develop/ui/compose/testing/interoperability).
Tags: `composer`, `send`, `presence`, `settings`, `camera`, `settings-back`,
`change-folder`, `copy-formatted`, `keep-photos`, `unpair`, `image-viewer`,
`image-viewer-back` e
`bubble-<localId>-<incoming|outgoing>-<pending|delivered|failed>`.
As tags são valores puros, sem prefixo do pacote. A entrega fica na tag da
bolha; o dump também contém o texto e, nas saídas, a legenda Entregue/Pendente.

## Primitivas do PC

Prefixo: `powershell -ExecutionPolicy Bypass -File .\qa\pc.ps1`.
`start` encerra as cópias de `pc.exe` e abre
`pc/src-tauri/target/release/pc.exe`, com a configuração e o Par reais.
Use `-Exe` para outro executável. Encerra a Sessão anterior do PC.
Define `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=--remote-debugging-port=9222`
e aguarda o CDP. `-Port` muda essa porta em **todas** as chamadas.
A saída JSON informa PID e arquivo de stderr, dentro de `-RunDir`.
Sem `-RunDir`, cria `.scratch/qa-runs/<data-hora>-redmi/`.
Leia `pc.stderr.log` para diagnosticar falhas do app.
O `start` não isolado registra PID e exe em
`.scratch/qa-runs/real-pc/state.json`, independente de `-RunDir`, antes de
aguardar o CDP. No fim, `down` só encerra e relança esse PID se o caminho do
exe ainda corresponder; registro de processo morto ou diferente é apenas
removido. `down -RealPcStatePath` permite injetar outro registro nos testes.

| Ação | Exemplo de argumentos | Resultado |
| --- | --- | --- |
| Abrir | `start -RunDir '.scratch/qa-runs/2026-09-30-redmi'` | PID, porta, stderr |
| Clicar | `click -Selector '.composer .send'` | `null` após clicar |
| Digitar | `type -Selector '#composer-input' -Text 'QA PC para celular'` | substitui o texto do campo |
| Tecla | `key -Selector '#composer-input' -Key Enter` | envia pelo composer |
| Ler DOM | `text -Selector '.item'` | lista dos textos de todas as bolhas |
| Print | `screenshot -Path '.scratch/qa-runs/2026-09-30-redmi/pc.png'` | captura da área web da janela |
| Comando Tauri | `invoke -Command list_items -Json '{}'` | retorno JSON do comando real |
| Presença | `presence` | `{"status":200,"online":true}` ou 204/false |
| Listar Pasta | `list-files -RunDir '<execução>'` | caminhos; fotografia inicial para proteger arquivos anteriores |
| Colar imagem | `paste-image -Path 'qa-foto.png'` | clipboard nativo do Windows, foco no composer e Ctrl+V pelo CDP; sobrescreve o clipboard do usuário e não o restaura |
| Colar arquivos | `paste-files -Paths 'qa-a.pdf','qa-b.txt'` | CF_HDROP nativo; uma bolha por arquivo; sobrescreve o clipboard do usuário e não o restaura |
| Registrar chegada | `track -Path 'C:\Users\bruno\Downloads\SynchroNice\qa-foto.png' -RunDir '<execução>'` | registra um arquivo novo da Pasta para limpeza |
| Limpar | `clean -RunDir '<execução>'` | destino confirmado: `Lixeira do Windows` ou `remocao permanente` |

Para vários caminhos, chame `& .\qa\pc.ps1 paste-files -Paths @('qa-a.pdf',
'qa-b.txt')` **dentro do PowerShell**: `powershell -File` não recebe arrays
tipados do shell chamador. Imagem e arquivos usam o clipboard real, sem
simular eventos paste nem chamar diretamente `send_files`/`send_bytes`.
`-Selector` muda o alvo de foco; o padrão é `#composer-input`.

`invoke` chama `window.__TAURI__.core.invoke`, sem reproduzir a lógica do app.
`presence` faz `HEAD http://127.0.0.1:47820/listen`, com o token lido de
`%APPDATA%/dev.synchronice.pc/config.json`. `-Config` troca esse caminho.
Nunca copie o token para relatórios. Cada ação CDP se desconecta no final;
o PC continua aberto. Seletores devem ser específicos; para várias bolhas,
`text` retorna todas. Prints precisam de diretório existente.

## Execução, retentativa e relatório

Para C.1, abra os dois chats, confira presença, digite no composer do PC e
aperte Enter. Leia as bolhas no DOM e no dump: texto igual nos dois lados,
bolha recebida `incoming-delivered` no celular e saída sem Pendente no PC.
Para C.2, toque `composer`, digite no celular e toque `send`; esconda o
teclado se necessário. Confira o texto no DOM e a bolha `outgoing-delivered`
no dump. `list_items` confirma o Item recebido pelo PC.
Escolha textos identificáveis com o nome e horário da execução.

Um passo instável tenta de novo **uma vez**, antes de ❌. Depois de enviar,
repita a leitura, não o envio: evita duplicar Itens. Um erro de conexão adb
pede uma nova chamada (que reconecta). Queda que não volta, ausência de
aparelho, tela bloqueada ou CDP indisponível abortam a execução como falha de
infraestrutura: não são bug do app nem reprovação do ticket. Marque os passos
não alcançados 👤, descrevendo a causa. Não marque como passou sem evidência.

Um arquivo por execução: `.scratch/qa-runs/<data>-<alvo>.md` (use horário
quando houver mais de uma execução). Commite o Markdown; prints e stderr
ficam no subdiretório homônimo, ignorado pelo git. Exemplo:

```markdown
# QA — 2026-09-30 — Redmi — SYN-022
Infraestrutura: Redmi sem fio, PC release, porta CDP 9222.

| Passo | Veredito | Evidência em texto |
| --- | --- | --- |
| C.1 | ✅ | DOM: QA PC 123; dump: bubble-1-incoming-delivered, texto QA PC 123; PC sem Pendente |
| C.2 | ❌ | Após duas leituras, bubble-2-outgoing-pending; DOM e list_items sem QA celular 123 |

Prints locais: 2026-09-30-redmi/pc.png e phone.png.
Limpeza: nenhum arquivo de usuário criado ou apagado; textos vivem na Sessão.
```

No walkthrough, ❌ abre `BUG-*` com `Status: needs-triage`, sem `Stage:`,
citando passo e ticket de origem. Na revisão de um ticket, ❌ reprova esse
ticket. Um passo verificado aponta o relatório e não gera débito humano.
👤 é o que permanece para o humano; o ticket SYN-022 permite débito nos
critérios 3–5 e 7 se o Redmi não estiver alcançável sem fio.
## Limpeza dos arquivos do QA

Escolha um `-RunDir` e repita-o em **todas** as ações de arquivos dos dois
wrappers. Antes de enviar, rode `list-files` em ambos: ele registra os
arquivos anteriores à execução em `pc-files.json`/`phone-files.json`.
No PC, a Pasta vem de `config.json`, ou Downloads/SynchroNice quando não
foi escolhida; no celular, a listagem cobre Download/SynchroNice.
Uma Pasta Android escolhida via SAF fica fora desta primitiva.

Use nomes exclusivos da execução. `share`/`push` registram automaticamente
o arquivo empurrado. Depois da entrega, compare a listagem e registre com
`track` o caminho **exato** que o QA fez chegar (incluindo sufixo de colisão).
Nunca registre outros arquivos só porque apareceram durante a execução.
`track` recusa os arquivos da fotografia inicial e os de fora da Pasta.
Fontes locais copiadas para o clipboard não entram automaticamente na lista.

`clean` percorre apenas os arquivos registrados, recusa diretórios e compara
o SHA-256 antes de apagar: arquivo alterado fica preservado e gera erro.
No PC, usa `FileSystem.DeleteFile` com `SendToRecycleBin` e confere a nova
entrada na Lixeira; se o Windows apagou sem reciclar, relata `remocao permanente`.
No celular, atualiza `is_trashed=1` no MediaStore e confirma por query ou pela
saída do caminho original após o update. Tenta a URI de Downloads se a URI
de arquivo não reciclar; só então usa `rm` no caminho registrado e confere
se sumiu. Se continuar presente, sai com erro e mantém o arquivo rastreado.
O manifesto fica vinculado à Pasta
ou ao aparelho; não o reutilize para outro alvo. Lista vazia não apaga nada.
Os resultados ficam também em `cleanup` nos manifestos, inclusive após
uma interrupção; `clean` pode ser repetido sem apagar arquivos adicionais.
Se um `push` for interrompido, mantenha a fonte local no mesmo caminho até
limpar. O kit confere o hash da fonte e compara o arquivo remoto ao prefixo
de mesmo tamanho da fonte; só esse conteúdo parcial pode ser removido.
Fonte ausente/alterada, conteúdo diferente ou manifesto antigo sem essa
evidência ficam preservados e geram erro.

Copie para o relatório cada `path` e `destination` retornados pelos dois
`clean`. Se o kit abortou antes de criar arquivos, registre lista vazia e
o motivo. Os manifestos ficam no subdiretório local ignorado pelo git;
o relatório Markdown é committed. Os textos somem ao encerrar a Sessão.
