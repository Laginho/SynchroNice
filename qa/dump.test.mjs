import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { findNode } from './dump.mjs';

const xml = readFileSync(new URL('./fixtures/chat.xml', import.meta.url), 'utf8');

test('finds the composer by resource-id and the centre of its bounds', () => {
  assert.deepEqual(findNode(xml, 'id', 'composer'), { x: 450, y: 2050 });
});
test('finds a node by its exact text', () => {
  assert.deepEqual(findNode(xml, 'text', 'Na rede'), { x: 820, y: 110 });
});
test('finds a node by content description', () => {
  assert.deepEqual(findNode(xml, 'description', 'Enviar mensagem'), { x: 920, y: 2050 });
});
test('decodes XML entities before comparing text', () => {
  assert.deepEqual(findNode(xml, 'text', 'oi & "olá" <3'), { x: 260, y: 250 });
});
test('returns not found for missing, malformed or empty bounds', () => {
  for (const id of ['absent', 'hidden', 'broken']) assert.equal(findNode(xml, 'id', id), null);
  assert.equal(findNode('', 'text', 'Na rede'), null);
});
