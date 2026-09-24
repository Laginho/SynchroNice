// @ts-ignore Node's native test runner provides these built-ins at runtime.
import { test } from "node:test";
// @ts-ignore Node's native test runner provides these built-ins at runtime.
import assert from "node:assert/strict";
// @ts-ignore Node's native test runner provides these built-ins at runtime.
import { readFileSync } from "node:fs";
import { segmentarTexto } from "./copiaveis.ts";

interface SegmentoEsperado {
  type: "text" | "link" | "copyable";
  [key: string]: string;
}

interface CasoCopiavel {
  name: string;
  input: string;
  expected: SegmentoEsperado[];
}

const arquivoDeCasos = new URL("../../testdata/copiaveis.json", import.meta.url);
const casos = JSON.parse(readFileSync(arquivoDeCasos, "utf8")).cases as CasoCopiavel[];

for (const caso of casos) {
  test(caso.name, () => {
    assert.deepStrictEqual(segmentarTexto(caso.input), caso.expected);
  });
}
