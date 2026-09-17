# SYN-006: Arrastar arquivo da bolha para outro programa
Stage: to-implement
Status: ready-for-agent
Blocked by: SYN-003
Review: agent

- Primary files:
  - pc/src-tauri/src/** (comando de drag nativo com caminho na Pasta)
  - pc/src-tauri/Cargo.toml (plugin de drag)
  - pc/ui/** (iniciar drag a partir da bolha de arquivo)

#### What to build

Toda bolha de arquivo no PC, imagem ou não, pode ser arrastada para fora da
janela e solta em outro programa (Explorer, navegador, editor) como o arquivo
real que está na Pasta. Não precisa achar o arquivo na Pasta. Bolha Pendente
ou em progresso não arrasta.

#### Acceptance criteria

1. Arrastar uma bolha de imagem recebida e soltar numa pasta do Explorer copia o arquivo para lá.
2. Arrastar uma bolha de PDF e soltar no navegador abre o PDF.
3. Arrastar uma bolha de imagem e soltar num campo de anexo de site funciona como se viesse do Explorer.
4. Uma bolha com envio em progresso não inicia drag.
5. Se o arquivo foi removido da Pasta, o drag não acontece e a bolha indica que o arquivo não está mais lá.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Todos os critérios são manuais; descrever cada um no Resolution.

## Tests stage 2 writes (own commit, red)

- Nenhum teste automático viável: o comportamento é integração com o shell do Windows. Se o comando Rust tiver lógica própria (por exemplo, recusar caminho fora da Pasta), um teste unitário para ela em `pc/src-tauri/tests/`.

## Comments
