// @ts-ignore Node's native test runner provides these built-ins at runtime.
import { test } from "node:test";
// @ts-ignore Node's native test runner provides these built-ins at runtime.
import assert from "node:assert/strict";
import { Bandeja } from "./bandeja.ts";

test("entradas preservam ordem e duplicatas; remover tira só a entrada escolhida", () => {
  const bandeja = new Bandeja<string>();
  const primeira = bandeja.adicionar("C:\\foto.png");
  const segunda = bandeja.adicionar("C:\\foto.png");
  bandeja.adicionar("C:\\documento.pdf");
  assert.deepStrictEqual(bandeja.entradas().map((entrada) => entrada.origem), [
    "C:\\foto.png", "C:\\foto.png", "C:\\documento.pdf",
  ]);
  assert.notEqual(primeira.id, segunda.id);
  bandeja.remover(segunda.id);
  assert.deepStrictEqual(bandeja.entradas().map((entrada) => entrada.origem), [
    "C:\\foto.png", "C:\\documento.pdf",
  ]);
  bandeja.remover(segunda.id);
  bandeja.remover(-1);
  assert.equal(bandeja.entradas().length, 2);
});
