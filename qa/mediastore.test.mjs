import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mediaUri } from './mediastore.mjs';

test('finds the requested path among MediaStore rows', () => {
  const output = 'Row: 0 _id=11, _data=/storage/emulated/0/Pictures/other.jpg\n'
    + 'Row: 1 _id=42, _data=/storage/emulated/0/Pictures/QA.jpg';
  assert.equal(mediaUri(output, '/storage/emulated/0/Pictures/QA.jpg'),
    'content://media/external/file/42');
});

test('keeps commas and spaces in the final _data column', () => {
  assert.equal(mediaUri('Row: 0 _id=73, _data=/storage/emulated/0/Pictures/QA, photo 1.jpg',
    '/storage/emulated/0/Pictures/QA, photo 1.jpg'), 'content://media/external/file/73');
});

test('missing, empty and malformed query results cannot select another file', () => {
  for (const output of ['', 'No result found.', 'Row: 0 _id=null, _data=/wanted.jpg',
    'Row: 0 _id=3, _data=/wanted.jpg.backup']) {
    assert.throws(() => mediaUri(output, '/wanted.jpg'), /não achado/);
  }
});
