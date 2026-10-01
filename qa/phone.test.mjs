import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

test('optional denied preparation warns; requested action, lockscreen and transport errors stay fatal', () => {
  const directory = mkdtempSync(join(tmpdir(), 'syn-024-phone-'));
  try {
    const adb = join(directory, 'adb.ps1');
    writeFileSync(adb, `
$command = $args -join ' '
if ($args[0] -eq 'connect') { 'connected'; exit 0 }
if ($command -match 'get-state') { 'device'; exit 0 }
if ($command -match 'settings put global|KEYCODE_WAKEUP|dismiss-keyguard') {
  if ($env:QA_FAKE_SCENARIO -eq 'transport') { 'error: device offline'; exit 1 }
  if ($env:QA_FAKE_SCENARIO -eq 'denied') { 'java.lang.SecurityException: WRITE_SECURE_SETTINGS'; exit 1 }
  exit 0
}
if ($command -match 'dumpsys window policy') {
  if ($env:QA_FAKE_SCENARIO -eq 'locked') { 'mShowingLockscreen=true' }
  else { 'mShowingLockscreen=false' }
  exit 0
}
if ($command -match 'KEYCODE_ENTER') { 'java.lang.SecurityException: INJECT_EVENTS'; exit 1 }
if ($command -match 'uiautomator dump') { 'UI hierarchy dumped'; exit 0 }
if ($command -match 'exec-out cat') { '<hierarchy><node text="QA" /></hierarchy>'; exit 0 }
exit 0
`, 'utf8');
    for (const scenario of ['denied', 'action', 'locked', 'transport']) {
      const result = spawnSync('powershell', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
        resolve('qa/phone.ps1'), scenario === 'action' ? 'key' : 'dump', '-Adb', adb,
        '-Serial', '127.0.0.1:5555', '-Key', 'KEYCODE_ENTER'], {
        encoding: 'utf8', timeout: 30000, env: { ...process.env, QA_FAKE_SCENARIO: scenario },
      });
      assert.ifError(result.error);
      if (scenario === 'denied') {
        assert.equal(result.status, 0, result.stderr);
        assert.equal(result.stdout.trim(), '<hierarchy><node text="QA" /></hierarchy>');
        assert.match(result.stderr, /aviso.*SecurityException/);
      } else {
        assert.equal(result.status, 1, `${scenario}: ${result.stdout} ${result.stderr}`);
        assert.match(result.stderr, { action: /INJECT_EVENTS/, locked: /bloqueado/, transport: /offline/ }[scenario]);
      }
    }
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test('cleanup recovers interrupted uploads only when their bytes match the source', () => {
  const directory = mkdtempSync(join(tmpdir(), 'syn-024-upload-'));
  try {
    const adb = join(directory, 'adb.ps1');
    const source = join(directory, 'synchronice-qa-test-upload.txt');
    const content = Buffer.concat([Buffer.from('upload payload\n'), Buffer.alloc(70000, 120)]);
    writeFileSync(source, content);
    writeFileSync(adb, `
$command = $args -join ' '
$target = Join-Path $env:QA_FAKE_STORAGE 'synchronice-qa-test-upload.txt'
if ($args[0] -eq 'connect') { 'connected'; exit 0 }
if ($command -match 'get-state') { 'device'; exit 0 }
if ($command -match 'getprop ro.serialno') { 'QA-phone'; exit 0 }
if ($command -match 'dumpsys window policy') { 'mShowingLockscreen=false'; exit 0 }
if ($command -match 'set -C') {
  if (Test-Path -LiteralPath $target) { 'file exists'; exit 1 }
  [IO.File]::WriteAllBytes($target, [byte[]]@()); exit 0
}
if ($args[2] -eq 'push') {
  $bytes = [IO.File]::ReadAllBytes($args[3])
  switch ($env:QA_FAKE_SCENARIO) {
    'empty' { $bytes = [byte[]]@() }
    'large-prefix' { $bytes = [byte[]]$bytes[0..65539] }
    'complete' { }
    'success' { }
    default { $bytes = [byte[]]$bytes[0..3] }
  }
  [IO.File]::WriteAllBytes($target, $bytes)
  if ($env:QA_FAKE_SCENARIO -eq 'success') { exit 0 }
  'error: device offline during push'; exit 1
}
if ($command.Contains('if [ -f')) { if (Test-Path -LiteralPath $target) { 'exists' }; exit 0 }
if ($command -match 'sha256sum') { (Get-FileHash -LiteralPath $target).Hash.ToLowerInvariant() + '  upload'; exit 0 }
if ($command -match 'wc -c') { (Get-Item -LiteralPath $target).Length; exit 0 }
if ($command -match 'content query') { 'No result found.'; exit 0 }
if ($command -match 'rm -f --') { Remove-Item -LiteralPath $target; exit 0 }
exit 0
`, 'utf8');
    for (const scenario of ['partial', 'empty', 'large-prefix', 'complete', 'changed', 'source-changed', 'oversized', 'missing', 'success', 'collision']) {
      const storage = join(directory, scenario);
      mkdirSync(storage);
      const target = join(storage, 'synchronice-qa-test-upload.txt');
      const unlisted = join(storage, 'unlisted.txt');
      writeFileSync(unlisted, 'unrelated');
      writeFileSync(source, content);
      if (scenario === 'collision') writeFileSync(target, 'existing photo');
      const run = action => spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
        resolve('qa/phone.ps1'), action, '-Path', source, '-RunDir', join(storage, 'run'),
        '-Adb', adb, '-Serial', '127.0.0.1:5555'], {
        encoding: 'utf8', timeout: 30000,
        env: { ...process.env, QA_FAKE_SCENARIO: scenario, QA_FAKE_STORAGE: storage },
      });
      const pushed = run('push');
      assert.ifError(pushed.error);
      assert.equal(pushed.status, scenario === 'success' ? 0 : 1, pushed.stdout + pushed.stderr);
      if (scenario === 'changed') writeFileSync(target, 'other bytes');
      if (scenario === 'source-changed') writeFileSync(source, 'changed source');
      if (scenario === 'oversized') writeFileSync(target, Buffer.concat([content, Buffer.from('extra')]));
      if (scenario === 'missing') rmSync(target);
      const cleaned = run('clean');
      assert.ifError(cleaned.error);
      const preserved = ['changed', 'source-changed', 'oversized'].includes(scenario);
      assert.equal(cleaned.status, preserved ? 1 : 0, `${scenario}: ${cleaned.stdout} ${cleaned.stderr}`);
      assert.equal(existsSync(target), preserved || scenario === 'collision', scenario);
      if (scenario === 'collision') assert.equal(readFileSync(target, 'utf8'), 'existing photo');
      if (!preserved && scenario !== 'collision') {
        assert.equal(JSON.parse(cleaned.stdout)[0].destination,
          scenario === 'missing' ? 'ja ausente' : 'rm (MediaStore indisponivel)');
        assert.equal(run('clean').status, 0);
      }
      assert.equal(readFileSync(unlisted, 'utf8'), 'unrelated');
    }
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test('wifi-off requires successful disable and restore, not just a connected adb', () => {
  const directory = mkdtempSync(join(tmpdir(), 'syn-024-wifi-'));
  try {
    const adb = join(directory, 'adb.ps1');
    writeFileSync(adb, `
$command = $args -join ' '
if ($args[0] -eq 'connect') { 'connected'; exit 0 }
if ($command -match 'get-state') { 'device'; exit 0 }
if ($command -match 'dumpsys window policy') { 'mShowingLockscreen=false'; exit 0 }
if ($command -match 'nohup') {
  if ($env:QA_FAKE_SCENARIO -eq 'launch-denied') { 'java.lang.SecurityException: CHANGE_WIFI_STATE'; exit 1 }
  exit 0
}
if ($command -match 'shell cat .*wifi.*log') {
  switch ($env:QA_FAKE_SCENARIO) {
    'async-denied' { 'java.lang.SecurityException: CHANGE_WIFI_STATE' }
    'restore-denied' { 'QA_WIFI_DISABLED'; 'java.lang.SecurityException: CHANGE_WIFI_STATE' }
    'unproven' { 'nohup started' }
    default { 'QA_WIFI_DISABLED'; 'QA_WIFI_RESTORED' }
  }
}
exit 0
`, 'utf8');
    for (const scenario of ['launch-denied', 'async-denied', 'restore-denied', 'unproven', 'success']) {
      const result = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
        resolve('qa/phone.ps1'), 'wifi-off', '-Seconds', '1', '-Adb', adb, '-Serial', '127.0.0.1:5555'], {
        encoding: 'utf8', timeout: 30000, env: { ...process.env, QA_FAKE_SCENARIO: scenario },
      });
      assert.ifError(result.error);
      assert.equal(result.status, scenario === 'success' ? 0 : 1, `${scenario}: ${result.stdout} ${result.stderr}`);
      if (scenario === 'success') assert.equal(JSON.parse(result.stdout).reconnected, true);
    }
  } finally { rmSync(directory, { recursive: true, force: true }); }
});
