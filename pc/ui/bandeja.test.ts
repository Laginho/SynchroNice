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

test("envio mantém as entradas em ordem e põe texto por último", () => {
  const bandeja = new Bandeja<string>();
  const foto = bandeja.adicionar("foto.png");
  const pdf = bandeja.adicionar("arquivo.pdf");
  assert.deepStrictEqual(bandeja.sequencia("  legenda  "), [
    { tipo: "entrada", entrada: foto },
    { tipo: "entrada", entrada: pdf },
    { tipo: "texto", texto: "legenda" },
  ]);
  assert.equal(bandeja.entradas().length, 2);
});

test("envio aceita só entradas, só texto ou nenhum dos dois", () => {
  const bandeja = new Bandeja<string>();
  assert.deepStrictEqual(bandeja.sequencia(""), []);
  assert.deepStrictEqual(bandeja.sequencia(" \n\t "), []);
  assert.deepStrictEqual(bandeja.sequencia("texto"), [{ tipo: "texto", texto: "texto" }]);
  const entrada = bandeja.adicionar("foto.png");
  assert.deepStrictEqual(bandeja.sequencia("  "), [{ tipo: "entrada", entrada }]);
});

test("sequência é um retrato: adicionar depois não muda o envio já iniciado", () => {
  const bandeja = new Bandeja<string>();
  const primeira = bandeja.adicionar("foto.png");
  const envio = bandeja.sequencia("");
  bandeja.adicionar("depois.pdf");
  assert.deepStrictEqual(envio, [{ tipo: "entrada", entrada: primeira }]);
});

test("limpar esvazia a bandeja e não reutiliza a identidade das entradas", () => {
  const bandeja = new Bandeja<string>();
  const antiga = bandeja.adicionar("foto.png");
  bandeja.adicionar("arquivo.pdf");
  bandeja.limpar();
  assert.deepStrictEqual(bandeja.entradas(), []);
  bandeja.limpar();
  const nova = bandeja.adicionar("foto.png");
  assert.notEqual(nova.id, antiga.id);
  assert.deepStrictEqual(bandeja.sequencia(""), [{ tipo: "entrada", entrada: nova }]);
});
