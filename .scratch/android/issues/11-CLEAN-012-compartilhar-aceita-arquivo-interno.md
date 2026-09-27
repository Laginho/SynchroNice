# CLEAN-012: Compartilhar aceita arquivos internos do próprio app
Stage: done
Status: ready-for-agent
Blocked by: none
Review: agent

- Primary files:
  - android/app/src/main/java/synchronice/android/ShareActivity.kt
  - android/app/src/test/java/synchronice/android/ (teste novo do Compartilhar)

#### What to build

Achado na revisão do PR #7 (sessão `sweatshop/2026-09-25-2127`). Fronteira de
confiança.

O Compartilhar copia para a fila qualquer URI que chegue no Intent, venha de
`EXTRA_STREAM` ou de `clipData`, e o `ShareActivity` é exportado. Um app
qualquer pode mandar ao SynchroNice uma URI `file://` apontando para os
arquivos internos do próprio SynchroNice (as preferências do Par, onde fica o
token, ou a fila), ou uma URI do FileProvider do próprio app. O SynchroNice lê
o arquivo com as permissões dele e o envia ao PC.

A correção: o Compartilhar só aceita URIs `content://` cuja authority não é a
do próprio app. Uma URI recusada é ignorada, e as demais do mesmo Intent seguem
normalmente. Se nenhuma sobra, vale o mesmo caminho de hoje para "nada a
compartilhar". Texto puro (`EXTRA_TEXT`) não muda.

#### Acceptance criteria

1. Uma URI `file://`, inclusive uma que aponte para os arquivos internos do
   app, não entra na fila de Pendentes, e nada do arquivo é lido.
2. Uma URI `content://` com a authority do FileProvider do próprio app não
   entra na fila.
3. Uma URI `content://` de outro provider continua entrando na fila, como hoje.
4. Num `ACTION_SEND_MULTIPLE` com URIs aceitas e recusadas, só as aceitas
   entram na fila.
5. Texto compartilhado continua entrando na fila.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Tests stage 2 writes (own commit, red)

Teste Robolectric do Compartilhar, com Par gravado e a fila observada:
- `file://` para as preferências do Par → fila vazia (critério 1);
- `content://` com a authority do próprio app → fila vazia (critério 2);
- `content://` de um provider de teste → um Pendente de arquivo (critério 3);
- `SEND_MULTIPLE` com as três → só o do provider de teste (critério 4).
- Critério 2 (reaberto): `content://0@<pacote>.camera/camera/foto.jpg` — a
  authority do FileProvider com prefixo de userId — não entra na fila. Vermelho
  enquanto o filtro comparar `uri.authority` cru.
- Critério 4 (reaberto): o `SEND_MULTIPLE` leva as três URIs, como a linha de
  cima já pedia; a de authority própria ficou de fora na primeira passada.

## Comments

- 2026-09-27 Criado a partir da revisão do PR #7 (Standards, bug 2). O stage 1
  foi feito na sessão interativa.
- 2026-09-27 Stage 2: o critério 2 não foi provado pelo caminho ponta-a-ponta
  descrito acima. `androidx.core.content.FileProvider` (`SimplePathStrategy`)
  compara o caminho canônico do arquivo com a raiz configurada usando `/`
  fixo; num host Windows o canônico vem com `\`, e a comparação nunca bate —
  travessia real do FileProvider é irreprodutível sob Robolectric neste
  ambiente, para qualquer authority. `ShadowContentResolver.registerProviderInternal`
  também não sobrepõe a authority da câmera porque ela já está declarada no
  manifesto (funciona para `"media"` em `ReceivedFileRestartTest` só por não
  haver provider real concorrente ali). O teste
  `contentUriFromOwnFileProviderIsFilteredOutBeforeReading` chama
  `sharedUris` por reflection (método seguiu `private`) e verifica que a URI
  já sai filtrada antes de qualquer leitura — mesmo código de produção, sem
  depender do ContentResolver. Os outros três testes seguem ponta-a-ponta
  (Par gravado, fila observada) como o plano previa. Nenhuma verificação
  manual ficou pendente disto — é um bug de ambiente (Windows), não de
  comportamento no dispositivo real.
- 2026-09-27 Stage 3: reescrita a frase acima, que dizia "Débito humano:
  nenhuma…". O foreman junta toda linha com esse marcador em
  `grep -rh 'Débito humano:'`, e ela entraria na lista como débito sem ser.
- 2026-09-27 Stage 2 (retomada após reabertura): trocado o filtro por uma
  consulta ao PackageManager pela authority sem o prefixo `<userId>@`
  (`packageManager.resolveContentProvider`), fechando o desvio
  `content://0@<pacote>.camera/...`; a lista de authorities escrita à mão saiu
  junto. Vermelho: 2 testes falharam antes da correção, por `AssertionError`
  (`contentUriWithUserIdPrefixForOwnFileProviderIsFilteredOutBeforeReading` e
  `sendMultipleOnlyQueuesTheAcceptedUri`) — Android 29 testes, 2 falhas. Verde:
  gate completo (fmt/lint/typecheck/testes) — Android 29 testes, PC 44 Rust +
  32 node, 0 falhas. Nada fora dos Primary files. Nenhuma verificação manual
  ficou pendente disto.

#### Review (2026-09-27, stage 3) — reaberto

Gate verde em `c2f1b4c`, e a separação teste-antes-de-código se sustenta:
`4178923` toca só `ShareActivityTest.kt`; `c2f1b4c` toca só `ShareActivity.kt`
e o ticket. Contagens: Android 28 testes (`ShareActivityTest` 4), PC 44 Rust
+ 32 node, 0 falhas. Nada fora dos Primary files. Não há linha `Proxy decided`
neste ticket.

O que fica para o stage 2:

- ❌ **Critério 2.** O filtro compara `uri.authority` como string crua, e dá
  para contorná-lo com um prefixo de userId:
  `content://0@synchronice.android.camera/camera/<foto>.jpg`.
  - `uri.authority` vale `"0@synchronice.android.camera"`, diferente das duas
    strings da lista, e a URI passa.
  - O `ContentResolver` do AOSP tira o prefixo antes de procurar o provider
    (`ContentProvider.getAuthorityWithoutUserId`, que é
    `substring(lastIndexOf('@') + 1)`). O userId 0 é o do próprio app, então a
    chamada cai no FileProvider da câmera. O `exported="false"` não barra o
    próprio app, e o arquivo é lido e enviado.
  - O alcance hoje se limita a `files/camera/` (`camera_paths.xml`), mas o
    critério, como está escrito, não vale.
  - Correção sugerida, e quem implementa escolhe: tirar o prefixo e
    perguntar ao sistema de quem é o provider, algo como
    `packageManager.resolveContentProvider(authority.substringAfterLast('@'), 0)?.packageName != packageName`.
    Isso também tira a lista escrita à mão, que já deixa de fora o
    `synchronice.android.androidx-startup` do manifesto mesclado, e a
    authority `"$packageName.camera"`, hoje repetida entre
    `ShareActivity.kt:61` e `MainActivity.kt:634`.
  - Portas (`:80`) e maiúsculas não são bypass: a busca do provider é exata e
    não resolve.
- ❌ **Teste do critério 4.** O `SEND_MULTIPLE` leva só duas das três URIs que
  "Tests stage 2 writes" pedia; a de authority própria ficou de fora. Ela é
  filtrada antes de qualquer leitura, então o problema do FileProvider no
  Windows não impede de incluí-la. Sem ela, nada prova que uma URI própria
  recusada no meio do lote deixa as outras seguirem.

Notas que não bloqueiam (fazer só se o stage 2 já estiver mexendo no trecho):

- O teste do critério 2 chama `sharedUris`, que é privada, por reflection, e
  essa é a única reflection em `android/`. A convenção do repo é `internal`,
  chamada direto do teste (`reconcilePendingDelivery`, `fileMimeType`,
  `decodePreview`).
- `isFromAnotherApp` devolve `false` para qualquer `file://`, inclusive de
  outro app, o que o nome não diz. `isForeignContentUri` diria.
- Os nomes de teste em camelCase entre crases não precisam das crases.
  `UnpairTest` e `ReceivedFileRestartTest` usam camelCase sem crase.
- A prova vermelho-verde não ficou registrada com contagens no ticket. Pela
  leitura do diff, os testes 1, 2 e 4 ficam vermelhos sem a correção e o 3 é
  de regressão (verde antes e depois). Registre as contagens reais na próxima
  passada.

#### Resolution (2026-09-27)

Verdict: Approve

Re-revisão dos dois ❌ da passada anterior e do diff desde `d9e5300`. Os dois
foram fechados, e nenhum achado novo bloqueia.

- ✅ **Critério 2.** `isForeignContentUri` tira o prefixo com
  `substringAfterLast('@')`, que é o mesmo corte de
  `ContentProvider.getAuthorityWithoutUserId` que o `ContentResolver` usa antes
  de adquirir o provider. Os dois lados partem do `uri.authority`
  decodificado, então `0%40` também não escapa. A pergunta ao
  `PackageManager` recusa a câmera e qualquer provider mesclado do próprio app,
  como o androidx-startup. Um provider de outro app que o Android 11+ esconde
  por package visibility volta `null` e é aceito, como pede o critério 3. Só
  passa o scheme `content` exato, então `file://`, `android.resource://` e
  `CONTENT://` são recusados.
- ✅ **Teste do critério 4.** O `SEND_MULTIPLE` agora leva as três URIs. A
  própria é a variante `0@`; a sem prefixo segue coberta pelo teste isolado.
- Separação teste-antes-de-código: `4178923` e `f9a48d1` tocam só
  `ShareActivityTest.kt`; `c2f1b4c` e `d753ec2` tocam só `ShareActivity.kt` e
  o ticket. Nada fora dos Primary files.
- Não há linha `Proxy decided` neste ticket.

Correção pequena do stage 3 (`ae30d43`, dentro dos Primary files, sem teste
novo). `sharedUris` virou `internal`, que é a convenção do repo, e as duas
cópias da reflection no teste saíram. O overload depreciado
`resolveContentProvider(String, Int)` ganhou `@Suppress("DEPRECATION")`,
como o arquivo já faz no `getParcelableExtra`. O KDoc que falava em
"comparação" foi acertado, e os nomes de teste perderam as crases. O teste do
lote ganhou um comentário dizendo por que a URI própria vai primeiro.

Fica sem bloquear: o critério 1 ("nada do arquivo é lido") é provado de forma
indireta. O teste vê a fila vazia, e o código filtra antes de
`copyToStaging`, mas nenhum teste observa a leitura em si. O "Tests stage 2
writes" pedia só "fila vazia".

Vermelho-verde, reconferido pelo stage 3 numa worktree com os testes de
`d753ec2` sobre o `ShareActivity.kt` de `c2f1b4c`. Resultado: 29 testes, 2
falhas por `AssertionError`.
`contentUriWithUserIdPrefixForOwnFileProviderIsFilteredOutBeforeReading`
falha com "Expected value to be false", e
`sendMultipleOnlyQueuesTheAcceptedUri` com `expected:<1> but was:<0>`.

Gate completo em `ae30d43`: `gate: passou`. Android 29 testes
(`ShareActivityTest` 5), PC 44 Rust + 32 node, 0 falhas.
