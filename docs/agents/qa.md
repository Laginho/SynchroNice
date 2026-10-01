# Kit de QA

O agente lê o roteiro em prosa, chama as primitivas e julga cada passo.
Instale a única dependência com `npm ci --prefix qa`; não baixa navegador.
O PC usa o WebView2 instalado, via [CDP do Playwright](https://playwright.dev/docs/api/class-browsertype#browser-type-connect-over-cdp).

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
| Colar imagem | `paste-image -Path 'qa-foto.png'` | clipboard nativo do Windows, foco no composer e Ctrl+V pelo CDP |
| Colar arquivos | `paste-files -Paths 'qa-a.pdf','qa-b.txt'` | CF_HDROP nativo; uma bolha por arquivo |
| Registrar chegada | `track -Path 'C:\Users\bruno\Downloads\SynchroNice\qa-foto.png' -RunDir '<execução>'` | registra um arquivo novo da Pasta para limpeza |
| Limpar | `clean -RunDir '<execução>'` | arquivos registrados enviados à Lixeira do Windows |

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
No PC, usa `FileSystem.DeleteFile` com `SendToRecycleBin`. No celular,
atualiza `is_trashed=1` no MediaStore e confirma por query; se não puder,
usa `rm` apenas no caminho registrado. O manifesto fica vinculado à Pasta
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
