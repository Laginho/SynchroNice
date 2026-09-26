# SYN-014: Android recebe arquivos
Stage: to-implement
Status: ready-for-agent
Blocked by: SYN-013
Review: agent

- Primary files:
  - android/app/** (download para a Pasta, bolha de arquivo, abrir, "Mudar pasta…")
  - android/protocol-client/src/main/kotlin/synchronice/protocolclient/ProtocolClient.kt (download para um destino em stream)
  - android/protocol-client/src/test/kotlin/**

#### What to build

Spec: `.scratch/android/spec.md`, histórias 27 a 30, 32 e 33, e a seção
"Pasta". Retoma os critérios adiados de SYN-009 (`.scratch/core/issues/09-*`),
com a mudança da spec nova: arquivo recebido é gravado ao chegar, não com um
toque.

Com o app aberto, cada arquivo anunciado pelo PC é baixado direto para a Pasta
do celular. O padrão é `Download/SynchroNice` via MediaStore (sem permissão,
aparece na galeria). A confirmação ao PC só vai depois de gravado. A bolha à
esquerda mostra progresso animado enquanto baixa. Depois mostra a miniatura, se
for imagem, ou nome, tipo e tamanho. Nome repetido não sobrescreve. Tocar abre
o arquivo no app padrão (o visualizador de imagem embutido é o SYN-015; até lá,
imagem também abre no app padrão).

O módulo cliente passa a baixar para um destino em stream, porque o MediaStore
e o seletor de pastas não dão um arquivo comum. A garantia de hoje continua: a
confirmação só vai depois do download completo.

A engrenagem ganha "Mudar pasta…", que abre o seletor de pastas do Android
(Storage Access Framework). A escolha persiste, e os próximos arquivos vão para
ela.

#### Acceptance criteria

1. O módulo cliente baixa um Item de arquivo para um destino em stream, com o mesmo progresso e a mesma confirmação só depois do download completo que o download para arquivo tem hoje.
2. Um download interrompido não confirma o Item ao PC.
3. Um arquivo enviado pelo PC com o app aberto é gravado em `Download/SynchroNice` sem nenhum toque e aparece na galeria (se imagem) ou no gerenciador de arquivos. Após morte do processo entre a criação de um documento SAF e o retorno da URI, a retomada não pode criar outra cópia nem sobrescrever um documento alheio. Antes de criar, persistir uma intenção ligada ao Par, id do Item e Pasta, com marcador aleatório exclusivo no nome pedido ao SAF. Só retomar automaticamente quando a consulta à Pasta encontrar exatamente um documento que preserve esse marcador; persistir seu `COLUMN_DOCUMENT_ID`/URI antes de escrever. Não remover o marcador por rename automático. Nome original, tamanho ou posição na listagem não provam identidade. Se o provedor alterar/ocultar o marcador, atrasar a consulta ou houver ambiguidade, manter o Item Pendente e mostrar erro recuperável: não escrever, apagar, criar outra cópia nem confirmar ao PC automaticamente.
4. A bolha mostra progresso animado durante o download, e depois a miniatura (imagem) ou nome, tipo e tamanho (outros).
5. Dois arquivos com o mesmo nome ficam os dois na Pasta, sem sobrescrever.
6. Tocar na bolha abre o arquivo no app padrão.
7. "Mudar pasta…" na engrenagem troca a Pasta; o próximo arquivo vai para a pasta escolhida e a escolha sobrevive a reabrir o app.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Critérios 3 a 7 são no aparelho: verificação manual.

## Tests stage 2 writes (own commit, red)

- `android/protocol-client/src/test/kotlin/`: download para um destino em stream
  contra o servidor falso (bytes iguais, progresso chegando a 100, confirmação
  enviada), e download interrompido sem confirmação. Red porque o download só
  aceita arquivo.
- `android/app/src/test/java/`: com `AppState` real e provedor SAF falso,
  interromper após o provedor criar o documento, antes de devolver a URI.
  Reabrir o app deve reencontrar o documento pelo marcador persistido,
  reaproveitar seu id/URI e terminar com uma cópia, sem sobrescrever outro
  documento de nome original igual. Provedor que muda o marcador ou não
  permite consulta inequívoca deve manter o Item Pendente, sem novo destino,
  escrita ou ACK automático. Pelo laço real de escuta, cobrir também ACK
  bem-sucedido seguido de falha ao remover o recibo: a próxima abertura limpa
  o recibo sem novo GET nem bolha presa em falha, mesmo sem novo anúncio do PC.

## Comments

- 2026-09-26 Proxy decidiu: não assumir que a Pasta SAF fique sem alterações externas. `createDocument` só devolve a identidade após criar, e o provedor pode alterar o nome; recuperar pelo nome comum arriscaria escrever em arquivo alheio. O marcador aleatório persistido antes da criação permite retomar somente um candidato inequívoco. Em provedores que não preservam/expõem esse marcador, falhar fechado e exigir intervenção para escolher outra Pasta ou reconciliar o Pendente; nunca adotar por nome/tamanho nem criar outra cópia às cegas. A limpeza de recibo após ACK continua no escopo. Fontes: https://developer.android.com/reference/android/provider/DocumentsProvider e https://developer.android.com/reference/android/provider/DocumentsContract.Document.

- 2026-09-26 Attempt 1 failed: exit 0. Log tail: SYN-014 ficou `blocked`, commit `5ea7fd9`, sem alterações de código. /  / Falta definir como identificar com segurança o arquivo SAF após crash, antes de receber sua URI. A proposta está no ticket. /  / Interrompi conforme [ticket-flow](C:/Users/bruno/.agents/skills/ticket-flow/SKILL.md): “Stage 2 that needs a decision […] sets `Stage: blocked`”.

- 2026-09-26 Red: gate -TestsOnly falhou na compilação dos dois testes novos porque downloadFile só aceita File e não tem onSaved. Os 44 testes Rust e 24 testes TS passaram; testes JVM existentes do app estavam up-to-date. Primeira tentativa no sandbox falhou por C:\.android; repetição com acesso ao ambiente Android confirmou o red esperado.

#### Stage 2 (2026-09-26)

- Implementado download para OutputStream com fechamento, validação do tamanho e publicação antes do ACK. O caminho File reutiliza essa implementação e mantém o temporário; falhas não confirmam o Item. O app guarda a URI na Sessão após publicar para repetir só o ACK quando necessário.
- Arquivos anunciados são recebidos automaticamente via MediaStore em Download/SynchroNice ou via SAF na Pasta escolhida. Destinos incompletos são removidos após falha; nomes repetidos preservam os arquivos existentes. A escolha da Pasta e a permissão persistente ficam fora do Par.
- Bolhas recebidas mostram progresso animado, miniatura quando imagem, nome/tipo/tamanho e abrem via ACTION_VIEW com acesso temporário de leitura. A engrenagem oferece Mudar pasta….
- Red-green: commit de testes `47eeb37` falha porque a API de stream ainda não existe; com a implementação, os dois testes passam, incluindo interrupção e falhas de fechamento/publicação sem ACK. Nenhum teste foi alterado no commit de implementação.
- Gate completo passou após a última alteração: 44 testes Rust, 24 TS, 56 do protocolo Android e 8 JVM do app; zero falhas. fmt/clippy, typecheck e lint Android passaram.
- Referência de armazenamento: https://developer.android.com/training/data-storage/shared/media (publicação com IS_PENDING) e https://developer.android.com/reference/android/provider/DocumentsContract (SAF).
- Débito humano: verificar critérios 3–7 no aparelho: presença na galeria/gerenciador, progresso e miniatura, nomes repetidos, abertura externa e troca/persistência da Pasta. Não havia aparelho conectado. O emulador SynchroNice_API37 foi iniciado, mas Computer Use recusou a inspeção com `Computer Use was not approved to use qemu-system-x86_64`; nenhuma validação visual foi declarada. Emulador encerrado após a tentativa.

#### Revisão (2026-09-26) — reaberto

Verdict: Reopen — o recebimento pode duplicar um Item depois de reiniciar; `Stage: to-implement`.

**Standards:** nenhuma violação obrigatória. Os nomes genéricos e a limpeza de
Sessão repetida são observações de cheiro, mas refatorá-los seria trabalho fora
do critério que caiu.

**Spec:** o fluxo publica o arquivo e guarda `item.id`/URI apenas na Sessão, em
`ChatMessage` e `receivedFiles`. Se o Android matar o processo depois da
publicação e antes ou durante o `ack`, a Sessão some, o Item continua Pendente no
PC e a próxima abertura baixa outra cópia. Isso contraria a regra retomada de
SYN-009: só o `ack` remove o Item, então um id já gravado precisa ser ignorado.

**❌ Critério 3:** persistir o estado mínimo do Item recebido até o `ack` ter
sucesso, ligado ao Par, para que uma nova `AppState` repita só a confirmação e
não crie outro arquivo. O teste novo deve usar o app real com servidor falso:
publicar o arquivo, falhar o primeiro `ack`, recriar `AppState` com o mesmo
armazenamento e provar que a retomada manda o `ack` sem novo GET nem segunda
cópia. Como exige teste novo, não é conserto de Stage 3.

O achado pequeno do tipo de arquivo foi corrigido em `cc59aa9`: a bolha agora
mostra a extensão (`PDF`) em vez do MIME cru (`application/pdf`). O gate completo
passou depois dessa correção: 44 testes Rust, 24 TS, 56 do protocolo Android e 8
JVM do app; fmt/clippy, typecheck e lint Android passaram. A separação original
continua limpa: `47eeb37` contém só ticket + testes e `0a39464` não toca testes.
