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
3. Um arquivo enviado pelo PC com o app aberto é gravado em `Download/SynchroNice` sem nenhum toque e aparece na galeria (se imagem) ou no gerenciador de arquivos.
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

## Comments

- 2026-09-26 Attempt 1 failed: exit 0. Log tail: SYN-014 ficou `blocked`, commit `5ea7fd9`, sem alterações de código. /  / Falta definir como identificar com segurança o arquivo SAF após crash, antes de receber sua URI. A proposta está no ticket. /  / Interrompi conforme [ticket-flow](C:/Users/bruno/.agents/skills/ticket-flow/SKILL.md): “Stage 2 that needs a decision […] sets `Stage: blocked`”.
