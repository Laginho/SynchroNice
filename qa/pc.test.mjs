import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

test('PC cleanup protects previous, unlisted, changed files and unsafe manifests', () => {
  const directory = mkdtempSync(join(tmpdir(), 'syn-024-pc-'));
  try {
    const folder = join(directory, 'Pasta');
    const config = join(directory, 'config.json');
    mkdirSync(folder);
    writeFileSync(config, JSON.stringify({ pasta: folder }));
    const previous = join(folder, 'synchronice-qa-test-prévia.txt');
    const unlisted = join(folder, 'synchronice-qa-test-unlisted.txt');
    const tracked = join(folder, 'synchronice-qa-test-recycle.txt');
    const changed = join(folder, 'synchronice-qa-test-changed.txt');
    const run = (action, path, runDir = join(directory, 'run')) => {
      const args = [
        resolve('qa/pc.ps1'), action, '-Config', config, '-RunDir', runDir,
        ...(path ? ['-Path', path] : [])];
      const command = '[Console]::OutputEncoding = [Text.Encoding]::GetEncoding(850); & ' +
        args.map(value => value.startsWith('-') ? value : "'" + value.replaceAll("'", "''") + "'").join(' ');
      const result = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass',
        '-EncodedCommand', Buffer.from(command, 'utf16le').toString('base64')], { encoding: 'utf8', timeout: 30000 });
      assert.ifError(result.error);
      return result;
    };
    writeFileSync(previous, 'previous');
    const listed = run('list-files');
    assert.equal(listed.status, 0, listed.stdout + listed.stderr);
    const refused = run('track', previous);
    assert.equal(refused.status, 1, refused.stdout + refused.stderr);
    assert.equal(readFileSync(previous, 'utf8'), 'previous');

    writeFileSync(unlisted, 'unlisted');
    writeFileSync(tracked, 'tracked');
    assert.equal(run('track', tracked).status, 0);
    const cleaned = run('clean');
    assert.equal(cleaned.status, 0, cleaned.stderr);
    assert.deepEqual(JSON.parse(cleaned.stdout), [{ path: tracked, destination: 'Lixeira do Windows' }]);
    assert.equal(existsSync(tracked), false);
    assert.equal(readFileSync(unlisted, 'utf8'), 'unlisted');
    assert.equal(readFileSync(previous, 'utf8'), 'previous');
    assert.equal(run('clean').status, 0);

    writeFileSync(changed, 'before');
    assert.equal(run('track', changed).status, 0);
    writeFileSync(changed, 'after');
    assert.equal(run('clean').status, 1);
    assert.equal(readFileSync(changed, 'utf8'), 'after');

    const unsafe = join(directory, 'unsafe');
    mkdirSync(unsafe);
    writeFileSync(join(unsafe, 'pc-files.json'), JSON.stringify({ folder,
      baseline: [{ value: [previous], Count: 1 }], tracked: [], cleanup: [] }));
    const rejected = run('clean', null, unsafe);
    assert.equal(rejected.status, 1);
    assert.match(rejected.stderr, /Manifesto inseguro/);
    assert.equal(readFileSync(previous, 'utf8'), 'previous');
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test('PC accepts a drive root as Pasta without touching the real drive root', () => {
  const directory = mkdtempSync(join(tmpdir(), 'syn-024-root-'));
  const drive = [...'ZYXWVUTSRQPONMLKJIH'].find(letter => !existsSync(`${letter}:\\`));
  assert.ok(drive, 'a free drive letter is needed for the temporary Pasta');
  const root = `${drive}:\\`;
  const mounted = spawnSync('subst.exe', [`${drive}:`, directory], { encoding: 'utf8' });
  assert.equal(mounted.status, 0, mounted.stderr);
  try {
    const config = join(directory, 'config.json');
    writeFileSync(config, JSON.stringify({ pasta: root }));
    const run = action => spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
      resolve('qa/pc.ps1'), action, '-Config', config, '-RunDir', join(directory, 'run'),
      '-Path', `${root}synchronice-qa-test-root.txt`], { encoding: 'utf8', timeout: 30000 });
    const listed = run('list-files');
    assert.ifError(listed.error);
    assert.equal(listed.status, 0, listed.stderr);
    writeFileSync(join(directory, 'synchronice-qa-test-root.txt'), 'new QA file');
    const tracked = run('track');
    assert.equal(tracked.status, 0, tracked.stderr);
    const cleaned = run('clean');
    assert.equal(cleaned.status, 0, cleaned.stderr);
    assert.deepEqual(JSON.parse(cleaned.stdout), [{ path: `${root}synchronice-qa-test-root.txt`, destination: 'remocao permanente' }]);
    assert.equal(existsSync(join(directory, 'synchronice-qa-test-root.txt')), false);
  } finally {
    const unmounted = spawnSync('subst.exe', [`${drive}:`, '/D'], { encoding: 'utf8' });
    assert.equal(unmounted.status, 0, unmounted.stderr);
    rmSync(directory, { recursive: true, force: true });
  }
});
