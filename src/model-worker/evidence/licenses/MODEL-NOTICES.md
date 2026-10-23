# Evidence models in Mini-AI

The model inventory in `worker.json` identifies the precise shipped files and
their SHA-256 digests. Models run locally; Python is not a runtime dependency.

* `intfloat/multilingual-e5-small`, original revision
  `614241f622f53c4eeff9890bdc4f31cfecc418b3`, MIT.
  Original model card: <https://huggingface.co/intfloat/multilingual-e5-small>.
  E5 licence source: <https://github.com/microsoft/unilm/blob/master/LICENSE>.
  The local encoder may contain trained derivative weights; the experiment
  report and model SHA identify that derivative. Its original tokenizer and
  384-dimensional normalized embedding interface are retained.
* When included, `BAAI/bge-reranker-v2-m3`, original revision
  `953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e`, Apache License 2.0.
  Original model card: <https://huggingface.co/BAAI/bge-reranker-v2-m3>.
  Its weights have not been fine-tuned in this research. ONNX graph sidecars
  are individually hashed and distributed with the graph.

SentencePiece tokenizer code, ONNX Runtime, Abseil, generation runtime and GPU
libraries have their own notices in this directory. A source locator or an OCR
confidence value does not certify semantic correctness or current legal force.
