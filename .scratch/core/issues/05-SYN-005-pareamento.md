# SYN-005: Pareamento por QR e anúncio na rede
Stage: to-implement
Status: ready-for-agent
Blocked by: SYN-002
Review: agent

- Primary files:
  - pc/src-tauri/src/** (geração do Par, rotação de token, anúncio mDNS)
  - pc/src-tauri/tests/server.rs
  - pc/src-tauri/Cargo.toml (mDNS, QR)
  - pc/ui/** (tela de QR, ação "parear de novo")

#### What to build

O PC mostra um QR quando não há Par, e sob demanda em "parear de novo". O QR
carrega o nome de instância do serviço, a porta e o token. O PC anuncia o
serviço por mDNS na rede local com esse nome de instância, para o celular
achar o IP atual sem digitar nada. Parear de novo gera token novo: o anterior
passa a ser recusado imediatamente. O Par (nome de instância e token) é
gravado na configuração e sobrevive a reiniciar.

#### Acceptance criteria

1. Sem Par gravado, a janela mostra um QR em vez do chat vazio; o conteúdo decodificado do QR contém nome de instância, porta e token.
2. Um leitor de mDNS na mesma rede (por exemplo `dns-sd -B` ou app de descoberta) vê o serviço com o nome de instância do QR.
3. "Parear de novo" mostra um QR com token diferente; requisições com o token antigo passam a receber recusa.
4. Reiniciar o app mantém o mesmo nome de instância e token.
5. O nome de instância é estável por instalação e não contém informação pessoal (nem nome do usuário do Windows).

#### Verification

    dns-sd -B _synchronice._tcp
    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Critérios 1, 2 e 3 (parte visual): manual.

## Tests stage 2 writes (own commit, red)

- `pc/src-tauri/tests/server.rs`: criar Par gera token e nome de instância e os grava; recriar a partir da configuração devolve os mesmos; rotacionar troca o token e o servidor recusa o antigo e aceita o novo. Vermelho porque o módulo de Par não existe.

## Comments
