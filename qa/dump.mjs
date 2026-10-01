import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

function decode(value) {
  return value.replace(/&(#x[\da-f]+|#\d+|amp|quot|apos|lt|gt);/gi, (_, entity) => {
    if (entity.startsWith('#')) return String.fromCodePoint(entity[1] === 'x'
      ? parseInt(entity.slice(2), 16) : Number(entity.slice(1)));
    return { amp: '&', quot: '"', apos: "'", lt: '<', gt: '>' }[entity];
  });
}

export function findNode(xml, kind, value) {
  const attribute = { id: 'resource-id', text: 'text', description: 'content-desc' }[kind];
  if (!attribute) throw new Error('Use id, text or description');
  // ponytail: only uiautomator node attributes; use an XML library if other XML is needed.
  for (const node of xml.matchAll(/<node\b[^>]*>/g)) {
    const attrs = Object.fromEntries([...node[0].matchAll(/([\w-]+)="([^"]*)"/g)]
      .map(([, name, text]) => [name, decode(text)]));
    if (attrs[attribute] !== value) continue;
    const bounds = /^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/.exec(attrs.bounds);
    if (!bounds) continue;
    const [, x1, y1, x2, y2] = bounds.map(Number);
    if (x2 > x1 && y2 > y1) return { x: Math.floor((x1 + x2) / 2), y: Math.floor((y1 + y2) / 2) };
  }
  return null;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [, , file, kind, value] = process.argv;
  console.log(JSON.stringify(findNode(readFileSync(file, 'utf8'), kind, value)));
}
