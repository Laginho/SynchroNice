# QA — 2026-10-02 — emulador — SYN-028 — revisão final

PC debug de `syn-028` (`21e27ec`: implementação `66e7a52` mais dois
comentários corrigidos), AVD `SynchroNice_API37`, adb `127.0.0.1:5555`,
HTTP 47820 e CDP 9222. RunDir:
`.scratch/qa-runs/2026-10-02-syn-028-final-review`.
`qa/up.ps1 -Sdk D:/AppData/Local/Android/Sdk` compilou os dois apps,
abriu o PC com dados e Pasta isolados e confirmou presença 200.

**Resultado: 16/16 cenários no executável passaram.** HTTP e comandos
Tauri reais; o CDP reteve e liberou pedidos/respostas para controlar a
ordem dos acontecimentos, sem substituir a lógica de produção.

| Passo / critério | Veredito | Evidência |
| --- | --- | --- |
| 1 — botão com chat vazio | ✅ | Zero bolhas e `#clear-all` invisível. Com três Itens, o botão permite o clique. |
| 1 — contagens e Desfazer | ✅ | Texto recebido, arquivo e texto enviado; clique deixa zero bolhas e aviso “3 Itens e 1 arquivo”. Backend ainda conserva os três. Barra de 5 s, escala 0,913 após 400 ms. Desfazer restaura as três bolhas; arquivo e SHA-256 preservados. |
| 1 e 2 — expiração e nova chegada | ✅ | Um texto recebido durante os 5 s permanece; a seleção anterior desaparece das duas listas. Arquivo ausente da Pasta e confirmado na Lixeira com origem e hash idênticos. |
| 3 — reload após exclusão concluída | ✅ | Depois de recarregar, só a chegada posterior permanece; nenhum dos Itens excluídos volta. |
| 4 — exclusão recebida comum | ✅ | HTTP 204 remove um recebido e um enviado; zero bolhas e nenhum aviso. |
| 1 — segundo clique | ✅ | A primeira seleção é aplicada imediatamente; Desfazer devolve apenas o Item da segunda janela. |
| 4 — snapshot atrasado do Desfazer | ✅ | Resposta real de `list_sent_items` retida depois do snapshot de um recebido. Exclusão por HTTP deixa backend e DOM vazios; liberar a resposta conserva zero bolhas. |
| 1 — progresso após os 5 s | ✅ | Pedido real de `apagar_tudo` retido; chunk de upload real após a expiração não recria bolha. Após liberar o comando e terminar o upload: HTTP 201, backend e DOM vazios. |
| 3 — reload de upload selecionado | ✅ | Upload `receiving` selecionado desaparece de `list_items`. Um comando real idempotente confirma a aplicação antes do reload. Zero bolhas antes e depois do término; HTTP 201 e backend vazio. |
| 4 — snapshot inicial atrasado | ✅ | Resposta inicial retida com um recebido capturado; exclusão por HTTP ocorre com `apagado` registrado. Liberar a resposta mantém zero bolhas. |
| 4 — registro de `apagado`, duas execuções | ✅ | Pedido real de registro retido; um recebido e um enviado são criados e excluídos por HTTP antes de liberar. Nenhuma bolha aparece; snapshot e duas leituras finais vazios. |
| 4 — registro de `item`, duas execuções | ✅ | Mesma sequência com o registro de `item` retido e `apagado` já ativo: recebido e enviado excluídos, zero bolhas antes e depois da liberação. |
| 4 — registro de `enviado`, duas execuções | ✅ | O recebido cria bolha com `item` já ativo; sua exclusão a remove enquanto `enviado` está retido. O enviado também é excluído. Após liberar: listas vazias e nenhuma bolha residual em duas leituras. |
| 5 — suíte existente | ✅ | Gate completo em primeiro plano, exit 0, `gate: passou`: Rust 83/83, UI 60/60, kit 14/14; fmt, Clippy e TypeScript verdes. Android `check` verde; XMLs `UP-TO-DATE`: 71 testes do app e 72 do cliente, zero falhas, erros ou pulos. |

A corrida interna entre a consulta da marca e o término do upload foi
verificada pelo teste determinístico de `visiveis` e pela inspeção dos
locks e da segunda listagem. O QA do executável exercitou o reload real
do upload selecionado. O gate precedeu a correção dos dois comentários;
o build de QA posterior compilou a branch com essa correção documental.

Registros do stage 2 conferidos: `2905b50` falhou com `E0425` para
`visiveis` e export ausente de `ligarOuvintes`. O mutante com uma única
listagem falhou no teste Rust (7 passaram, 1 falhou); o mutante com a ordem
antiga dos ouvintes falhou no teste da UI (59 passaram, 1 falhou).
`66e7a52` não modifica testes; o gate atual passou. Extratos locais em
`stage2-red-green.log`.

Capturas `undo-notice.png` e `reload-stays-cleared.png` inspecionadas:
aviso e Desfazer legíveis, barra acima do composer, chat vazio sem 🗑.
Demais capturas e `runtime-evidence.json` ficam no RunDir ignorado pelo git.
Sem débito humano neste recorte. Limite conhecido: recarregar durante os
5 s restaura a conversa, pois o comando de exclusão ainda não foi aplicado.

## Limpeza

Pasta isolada do PC vazia. Os três arquivos criados pela execução estão
na Lixeira; origem conferida e SHA-256 idêntico aos bytes enviados:

| Arquivo na Pasta isolada | Destino confirmado |
| --- | --- |
| `syn028-base-murnu4ri.txt` | `D:\$RECYCLE.BIN\S-1-5-21-2960696925-1232010053-3133060791-1001\$RRAOKIK.txt` |
| `syn028-progress-murnu4ri.bin` | `D:\$RECYCLE.BIN\S-1-5-21-2960696925-1232010053-3133060791-1001\$RS88JKV.bin` |
| `syn028-reload-murnu4ri.bin` | `D:\$RECYCLE.BIN\S-1-5-21-2960696925-1232010053-3133060791-1001\$R1MT32F.bin` |

SHA-256 do texto:
`86901A536002F78B030F1FC2F7619FB3A48E6A50101EE5E6A0E661C172AFE5B0`.
SHA-256 de cada upload de 196.608 bytes:
`E89D548F5062211A848D4B55B04B8096EB4C397C1EF5E10312245CEF3D00C265`.
Evidências locais em `recycle-evidence.json` e `upload-recycle-evidence.json`.

`pc.ps1 clean`: path
`D:\Desktop\Projects\SynchroNice\.scratch\qa-runs\2026-10-02-syn-028-final-review\data\Pasta\syn028-base-murnu4ri.txt`,
destination `ja ausente`, pois o próprio app já o reciclou.
`phone.ps1 clean`: lista vazia; nenhum arquivo novo no emulador, e os dez
arquivos anteriores continuam presentes. Textos limitados à Sessão.

`qa/down.ps1` concluído, exit 0, `stopped=true`, `restored=false`:
emulador e PC isolado encerrados; não havia PC real a restaurar.
A regravação de `Cargo.toml` pela CLI, sem mudança de conteúdo, foi desfeita.
