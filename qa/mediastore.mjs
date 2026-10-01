import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

export function mediaUri(output, path) {
  // ponytail: _data is last in the _id:_data projection; do not split paths on commas.
  for (const line of output.split(/\r?\n/)) {
    const row = /^Row: \d+ _id=(\d+), _data=(.*)$/.exec(line);
    if (row && row[2] === path) return `content://media/external/file/${row[1]}`;
  }
  throw new Error(`não achado no MediaStore: ${path}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    console.log(mediaUri(readFileSync(process.argv[2], 'utf8'), process.argv[3]));
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
