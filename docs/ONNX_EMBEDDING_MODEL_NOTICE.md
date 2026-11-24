# AURA local semantic embedding model notice

AURA uses the quantized ARM64 ONNX export of **sentence-transformers/all-MiniLM-L6-v2** for local semantic retrieval.

- Upstream repository: https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2
- Pinned revision: `1110a243fdf4706b3f48f1d95db1a4f5529b4d41`
- Model artifact: `onnx/model_qint8_arm64.onnx`
- Model SHA-256: `4278337fd0ff3c68bfb6291042cad8ab363e1d9fbc43dcb499fe91c871902474`
- Vocabulary: `vocab.txt`
- Vocabulary SHA-256: `07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3`
- Embedding dimension: 384
- License: Apache-2.0 (upstream model card)

The app downloads these pinned artifacts over HTTPS only while Android reports an unmetered network and the battery is not low. It verifies each SHA-256 digest before atomically publishing the files. Model text, tokenizer inputs, and embedding vectors remain on-device. The semantic index encrypts complete memory records using an Android Keystore AES-GCM key.

The model is English-centric; its embeddings should not be treated as a multilingual Bengali/Hindi embedding model. AURA's existing encrypted lexical memory search remains available independently of this optional semantic index.

ONNX Runtime Java is pinned to 1.27.0 to match the ONNX Runtime version bundled by the app's sherpa-onnx v1.13.4 native AAR. The Android package resolves the identical ARM64 runtime shared object once.
