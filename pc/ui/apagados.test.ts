// @ts-ignore Node's native test runner provides these built-ins at runtime.
import { test } from "node:test";
// @ts-ignore Node's native test runner provides these built-ins at runtime.
import assert from "node:assert/strict";
import { Apagados } from "./apagados.ts";

const ids = (itens: { id: number }[]) => itens.map((item) => item.id);

test("sem exclusão marcada, nada é filtrado e a ordem se mantém", () => {
  const apagados = new Apagados();
  assert.deepStrictEqual(ids(apagados.filtrarRecebidos([{ id: 3 }, { id: 1 }])), [3, 1]);
  assert.deepStrictEqual(ids(apagados.filtrarEnviados([{ id: 2 }])), [2]);
  assert.equal(apagados.recebido(1), false);
  assert.equal(apagados.enviado(1), false);
});

test("um id marcado fica de fora para sempre, também num snapshot atrasado", () => {
  const apagados = new Apagados();
  apagados.marcar([1, 2], [5]);
  for (let vez = 0; vez < 2; vez++) {
    assert.equal(apagados.recebido(1), true);
    assert.deepStrictEqual(ids(apagados.filtrarRecebidos([{ id: 1 }, { id: 2 }, { id: 3 }])), [3]);
    assert.deepStrictEqual(ids(apagados.filtrarEnviados([{ id: 5 }, { id: 6 }])), [6]);
  }
});

test("marcar de novo acumula, e recebidos e enviados têm sequências separadas", () => {
  const apagados = new Apagados();
  apagados.marcar([1], []);
  apagados.marcar([], [1, 2]);
  assert.equal(apagados.recebido(1), true);
  assert.equal(apagados.recebido(2), false);
  assert.equal(apagados.enviado(1), true);
  assert.equal(apagados.enviado(2), true);
  assert.deepStrictEqual(ids(apagados.filtrarRecebidos([{ id: 1 }, { id: 2 }])), [2]);
});
