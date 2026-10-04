# Helper compartilhado entre os wrappers do kit de QA

Os wrappers do kit de QA (`qa/up.ps1`, `qa/down.ps1`, `qa/pc.ps1`,
`qa/phone.ps1`) não carregam código uns dos outros. Uma função que aparece
em mais de um deles fica copiada em cada um.

## Por que está fora do escopo

O caso que trouxe a questão é o `Stop-AndWait`: 18 linhas idênticas em
`up.ps1`, `down.ps1` e `pc.ps1`. Elas guardam o handle do processo, forçam a
parada, esperam 10 s e, se o prazo estoura, lançam erro de infraestrutura com
o PID. Nenhuma cópia tem defeito e a regra é estável.

Extrair para um arquivo carregado com dot-source (`. "$PSScriptRoot/lib.ps1"`)
faria cada wrapper depender de um caminho e de um arquivo a mais. Hoje cada um
roda sozinho com `powershell -File qa/<nome>.ps1`, e é assim que o loop, o
foreman e o proxy os chamam. Corrigir em um lugar só não paga esse
acoplamento enquanto a regra não muda.

Reabrir se a regra de parada mudar (prazo, tratamento de erro, espera) e a
mudança tiver de ser feita nas cópias. Aí a manutenção em um lugar só passa a
valer o arquivo a mais.

## Pedidos anteriores

- CLEAN-030: "Avaliar helper compartilhado de parada com espera no kit"
  (`.scratch/qa/issues/18-CLEAN-030-parada-com-espera-compartilhada.md`)
