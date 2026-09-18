# SYN-009: Android recebe arquivos do PC
Stage: to-implement
Status: ready-for-agent
Blocked by: SYN-007, SYN-004
Review: agent

- Primary files:
  - android/** (download de Item de arquivo, cache de miniaturas, bolha de arquivo com progresso, ação de salvar)

#### What to build

Itens de arquivo mandados do PC aparecem no chat do celular como bolha à
esquerda. Imagem mostra miniatura carregada do cache do app, sem ir para a
galeria. Outro tipo mostra nome, extensão e tamanho. Durante o download a
bolha mostra progresso animado. Um toque na bolha salva: imagem vai para a
galeria, outro arquivo para Downloads, e a bolha indica que foi salvo. Um
toque em texto que é link abre o navegador.

#### Acceptance criteria

1. Uma imagem enviada do PC aparece como miniatura no celular sem aparecer na galeria.
2. Um toque na miniatura salva a imagem na galeria e a bolha indica "salvo".
3. Um PDF enviado do PC aparece com nome, `PDF` e tamanho; um toque o salva em Downloads.
4. Durante o download de um arquivo grande a bolha mostra progresso animado.
5. Um link enviado do PC abre no navegador ao tocar.
6. Fechar o app descarta as bolhas; o que foi salvo permanece na galeria ou em Downloads.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Critérios 1 a 6: manual, com PC real.

## Tests stage 2 writes (own commit, red)

- `android/.../test/ProtocolClientTest.kt`: ao receber anúncio de Item de arquivo, o cliente baixa pela rota de download com progresso crescente e entrega bytes idênticos; download interrompido não marca o Item como completo. Vermelho porque o download não existe.

## Comments
