# BUG-001: O scanner de QR derruba o app no primeiro quadro sem QR
Stage: to-merge
Status: ready-for-agent
Blocked by: none
Review: agent

- Primary files:
  - android/app/src/main/java/synchronice/android/QrCameraPreview.kt (`QrAnalyzer.decode`; nada mais)
  - android/app/src/test/java/synchronice/android/QrAnalyzerTest.kt (novo)

#### What to build

Achado do walkthrough de QA (2026-09-30, passo B.1–B.2, Redmi com Android 16).
Sintoma: a câmera "desiste" poucos segundos depois de abrir; só pareia se o app
já abre com o QR enquadrado, e mesmo assim nem sempre.

É crash. O `logcat -b crash` tem 13 ocorrências iguais em 30 minutos:

    java.lang.UnsupportedOperationException: This luminance source does not support rotation by 90 degrees.
        at com.google.zxing.LuminanceSource.rotateCounterClockwise(LuminanceSource.java:119)
        at synchronice.android.QrAnalyzer.decode(QrCameraPreview.kt:128)

`decode` tenta ler o quadro e, quando falha, gira a fonte e tenta de novo, até
4 vezes. `PlanarYUVLuminanceSource` não implementa rotação: o primeiro quadro
sem QR legível lança a exceção na thread do analisador e mata o processo. Só
passa quando o QR é lido na primeira tentativa, sem girar.

Correção: **tirar o laço de rotação.** O detector de QR do ZXing acha os
padrões de localização em qualquer orientação, então girar não acrescenta
nada. Decodifica uma vez, e `ReaderException` devolve `null`.

#### Acceptance criteria

1. `decode` de um quadro sem QR (luminância toda cinza) devolve `null` sem lançar.
2. `decode` de um quadro com o QR do Par, girado 90° dentro do quadro, devolve o
   JSON do QR (prova que a rotação manual não fazia falta).
3. Os testes existentes seguem verdes.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Comments
