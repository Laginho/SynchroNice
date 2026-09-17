# 0001 — Tauri no PC, Kotlin no Android

Data: 2026-09-16 · Status: aceito

## Contexto

O app precisa de: bandeja e autostart no Windows, arrastar arquivo do chat
para outros programas, Ctrl+V de arquivos, receber "Compartilhar" do Android,
descoberta do PC na rede local e serviço em segundo plano no celular. Requisito
forte do autor: leve em disco e RAM. Electron descartado por isso. O autor
domina JS/TS, Python, C/C++ e aceita aprender Rust.

## Decisão

- PC: **Tauri** (backend Rust, interface HTML/JS no WebView2 do Windows).
- Android: **Kotlin nativo**.
- Bases de código separadas; protocolo comum na rede local.

## Alternativas rejeitadas

- **Electron**: peso em disco e RAM.
- **.NET/WinUI**: leve, mas a interface é outro ecossistema e descarta o
  conhecimento web do autor.
- **Tauri também no Android**: Compartilhar e segundo plano exigiriam plugin
  em Kotlin de qualquer forma; a camada extra não paga.
- **Flutter nos dois**: um código só, mas app maior e Dart em tudo.

## Consequências

- Rust fica restrito a rede, pasta, clipboard e bandeja.
- Duas linguagens novas (Rust, Kotlin), cada uma em superfície pequena.
- O protocolo entre os lados precisa ser documentado, pois ninguém compila os
  dois de um único fonte.
