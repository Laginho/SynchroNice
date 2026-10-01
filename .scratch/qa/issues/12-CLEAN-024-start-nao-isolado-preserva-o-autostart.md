# CLEAN-024: `pc.ps1 start` não isolado preserva o autostart real
Status: needs-triage
Blocked by: none
Origin: revisão do PR 8 (sessão sweatshop/2026-09-30-2146), 2026-10-01

## Comments

- Sem `-Isolated`, `qa/pc.ps1:26-28` abre `-Exe`, ou o `target/release/pc.exe`
  relativo ao script, com os dados reais, e `:128` mata todo `pc`. O app
  chama `autolaunch().enable()` em todo start não isolado
  (`pc/src-tauri/src/lib.rs`, `setup`), então o `HKCU\...\Run` passa a apontar
  para esse exe. Um `start -Exe <debug>`, ou um run a partir de outro checkout
  ou de uma worktree temporária, deixa o autostart num build de debug ou num
  caminho que some depois. Isso contraria a história 7 da spec.
- Também: esse start deixa o PC real com CDP na 9222 e stdout/stderr no RunDir,
  e nada o devolve ao normal depois.
- Proposta para triagem: recusar quando o exe não é o do valor atual do Run, ou
  o da instância real em execução, e reabrir o real sem CDP no fim do pós-run.
