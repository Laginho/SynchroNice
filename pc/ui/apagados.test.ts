// @ts-ignore Node's native test runner provides these built-ins at runtime.
import { test } from "node:test";
// @ts-ignore Node's native test runner provides these built-ins at runtime.
import assert from "node:assert/strict";
import { Apagados, ligarOuvintes } from "./apagados.ts";

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

type Tratador = (event: { payload: unknown }) => void;

/** Um `listen` que só vale depois de um turno do laço de eventos, como o IPC do
 * Tauri. Depois de cada registro, o backend aproveita a brecha: um Item novo
 * (recebido e enviado) chega e é excluído logo em seguida, com id inédito. */
function backendComBrechas() {
  const tratadores = new Map<string, Tratador>();
  const emitir = (nome: string, payload: unknown) => tratadores.get(nome)?.({ payload });
  let proximo = 0;
  const escutar = async (nome: string, tratar: Tratador) => {
    await new Promise((resolve) => setTimeout(resolve, 0));
    tratadores.set(nome, tratar);
    const id = ++proximo;
    emitir("item", { id });
    emitir("enviado", { id });
    emitir("apagado", { received: [id], sent: [id] });
  };
  return { escutar, emitir };
}

test("ligarOuvintes: um Item excluído logo após o registro de qualquer ouvinte não deixa bolha", async () => {
  const { escutar } = backendComBrechas();
  const recebidas = new Set<number>();
  const enviadas = new Set<number>();
  await ligarOuvintes(escutar, {
    item: (item: { id: number }) => recebidas.add(item.id),
    enviado: (item: { id: number }) => enviadas.add(item.id),
    apagado: (excluidos: { received: number[]; sent: number[] }) => {
      for (const id of excluidos.received) recebidas.delete(id);
      for (const id of excluidos.sent) enviadas.delete(id);
    },
  });
  assert.deepStrictEqual([...recebidas], []);
  assert.deepStrictEqual([...enviadas], []);
});

test("ligarOuvintes: cada evento chega ao seu tratador", async () => {
  const { escutar, emitir } = backendComBrechas();
  const vistos: [string, unknown][] = [];
  await ligarOuvintes(escutar, {
    item: (payload: unknown) => vistos.push(["item", payload]),
    enviado: (payload: unknown) => vistos.push(["enviado", payload]),
    apagado: (payload: unknown) => vistos.push(["apagado", payload]),
  });
  vistos.length = 0;
  emitir("item", "i");
  emitir("enviado", "e");
  emitir("apagado", "a");
  assert.deepStrictEqual(vistos, [
    ["item", "i"],
    ["enviado", "e"],
    ["apagado", "a"],
  ]);
});
