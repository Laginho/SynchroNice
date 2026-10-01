import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
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
