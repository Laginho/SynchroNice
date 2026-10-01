# CLEAN-025: Acertos menores do kit de QA
Status: needs-triage
Blocked by: none
Origin: revisão do PR 8 (sessão sweatshop/2026-09-30-2146), 2026-10-01

## Comments

- Itens pequenos achados na mesma revisão. A triagem pode dividir:
  - `qa/phone.ps1:317` (`type`) e `:307` (`tap -Value` para o node) não
    escapam `"` para a camada de argv do PS 5.1. `'say "hi"'` chega como
    `say hi` com código 0, e o agente pode registrar um ❌ falso.
  - `Stop-Process -Force` sem `Wait-Process` em `qa/up.ps1:27,115` e
    `qa/down.ps1:34`. O check seguinte, ou o bind da 47820, ainda pode ver o
    processo morrendo.
  - `qa/pc.ps1:156-161` espera o PID do launcher por 6 s. Se o prazo estoura,
    o launcher ainda abre o PC depois, possivelmente já após o `down`.
  - `qa/pc.ps1:86-90` sempre relata `Lixeira do Windows`, mas sem Lixeira no
    drive, ou acima da cota, o delete é permanente. Afeta só arquivos que o kit
    rastreou, mas o relatório da história 10 fica errado. O caso do MediaStore
    já está em CLEAN-019.
  - `paste-image`/`paste-files` sobrescrevem o clipboard do usuário sem
    restaurar.
