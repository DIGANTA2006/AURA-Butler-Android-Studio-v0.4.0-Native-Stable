# Third-party notices

This personal project bundles the following components.

## sherpa-onnx 1.13.4

- Project: https://github.com/k2-fsa/sherpa-onnx
- Android AAR: `com.github.k2-fsa:sherpa-onnx:v1.13.4`
- License: Apache License 2.0

## Whisper Tiny multilingual ONNX

- Model family: OpenAI Whisper
- sherpa-onnx export:
  https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models
- Bundled quantized encoder, quantized decoder, and token table

Review the upstream Whisper license before redistribution.

## GigaSpeech keyword-spotting model

- Model:
  `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01`
- Source:
  https://github.com/k2-fsa/sherpa-onnx/releases/tag/kws-models
- License recorded by the model card: Apache License 2.0
- This package uses only the quantized encoder/decoder/joiner and token table,
  plus the custom `Aura` keyword file.

## Piper en_US-lessac-medium

- Model: `vits-piper-en_US-lessac-medium`
- Source:
  https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
- Dataset and license:
  https://www.cstr.ed.ac.uk/projects/blizzard/2013/lessac_blizzard2013/license.html
- Bundled model, tokens, and eSpeak NG data

This package is intended as a personal prototype. Re-check every upstream
license before commercial distribution or publishing to an app store.

