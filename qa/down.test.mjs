import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, readdirSync, copyFileSync, rmSync, existsSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { createServer } from 'node:net';
import { setTimeout as delay } from 'node:timers/promises';

const quote = value => `'${value.replaceAll("'", "''")}'`;
async function freeSerial() {
  const server = createServer();
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  assert.notEqual(port, 5554);
  return `emulator-${port}`;
}

function powershell(command) {
  const result = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command',
    `$ErrorActionPreference = 'Stop'; ${command}; exit 0`], { encoding: 'utf8', timeout: 30000 });
  assert.ifError(result.error);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  return result.stdout.trim();
}

async function checkListenerShutdown(exitDelay) {
  const directory = mkdtempSync(join(tmpdir(), 'clean-032-down-'));
  const ready = join(directory, 'ready');
  const killed = join(directory, 'killed');
  const exited = join(directory, 'exited-naturally');
  let child;
  let childExited;
  const alive = () => {
    try { process.kill(child.pid, 0); return true; }
    catch (error) { if (error.code === 'ESRCH') return false; throw error; }
  };
  try {
    const stub = join(directory, 'listener.cjs');
    writeFileSync(stub, `
const fs = require('node:fs');
const net = require('node:net');
const server = net.createServer();
server.listen(0, '127.0.0.1', () => fs.writeFileSync(${JSON.stringify(ready)}, String(server.address().port)));
const polling = setInterval(() => {
  if (!fs.existsSync(${JSON.stringify(killed)})) return;
  clearInterval(polling);
  ${exitDelay === null ? '' : `setTimeout(() => {
    fs.writeFileSync(${JSON.stringify(exited)}, '');
    process.exit(0);
  }, ${exitDelay});`}
}, 50);
setTimeout(() => process.exit(2), 120000);
`);
    child = spawn(process.execPath, [stub], { windowsHide: true, stdio: 'ignore' });
    childExited = new Promise((resolve, reject) => {
      child.once('exit', resolve);
      child.once('error', reject);
    });
    const deadline = Date.now() + 10000;
    while (!existsSync(ready)) {
      assert.ok(Date.now() < deadline && child.exitCode === null, 'listener did not start');
      await delay(50);
    }
    const port = Number(readFileSync(ready, 'utf8'));
    assert.notEqual(port, 5554);
    const serial = `emulator-${port}`;
    const statePath = join(directory, 'emulator.json');
    writeFileSync(statePath, JSON.stringify({ active: true, serial, realWasRunning: false }));
    const adb = join(directory, 'adb.ps1');
    writeFileSync(adb, `
if ($args[0] -eq 'devices') {
  'List of devices attached'
  if (-not (Test-Path -LiteralPath ${quote(killed)})) { "${serial}\tdevice" }
  exit 0
}
if (($args -join ' ') -eq '-s ${serial} emu avd name') { 'SynchroNice_API37'; exit 0 }
if (($args -join ' ') -eq '-s ${serial} emu kill') { [IO.File]::WriteAllText(${quote(killed)}, ''); exit 0 }
exit 1
`);
    const result = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
      resolve('qa/down.ps1'), '-RunDir', directory, '-Adb', adb,
      '-DebugExe', join(directory, 'unused-debug.exe'), '-ReleaseExe', join(directory, 'unused-release.exe'),
      '-RealPcStatePath', join(directory, 'unused-real.json')], { encoding: 'utf8', timeout: 60000 });
    assert.ifError(result.error);
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(alive(), false, 'down returned while the listener process was still alive');
    assert.equal(existsSync(exited), exitDelay !== null, 'graceful shutdown must let the stub exit on its own');
    if (exitDelay === null) {
      assert.ok(Date.now() - statSync(killed).mtimeMs >= 30000, 'forced stop must allow the full 30-second grace period');
    }
    assert.equal(JSON.parse(readFileSync(statePath, 'utf8').replace(/^\uFEFF/, '')).active, false);
  } finally {
    if (child) {
      if (alive()) child.kill();
      await childExited;
    }
    assert.ok(directory.startsWith(join(tmpdir(), 'clean-032-down-')));
    rmSync(directory, { recursive: true, force: true });
  }
}

test('down waits for the emulator listener to exit naturally after adb drops the serial', async () => {
  await checkListenerShutdown(8000);
});

test('down forces the emulator listener to stop after the 30-second grace period', async () => {
  await checkListenerShutdown(null);
});

test('down restores the real PC even when emulator cleanup fails, and can be repeated', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'clean-020-down-'));
  const stub = join(directory, 'pc.exe');
  try {
    // A real Windows executable: WMI launches it just like the application.
    // Each launch records its inherited environment beside its own executable.
    const source = join(directory, 'Stub.cs');
    writeFileSync(source, `
using System;
using System.Diagnostics;
using System.IO;
using System.Threading;
public class Stub {
  public static void Main() {
    string directory = AppDomain.CurrentDomain.BaseDirectory;
    string[] names = { "SYNCHRONICE_DATA_DIR", "WEBVIEW2_USER_DATA_FOLDER", "WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS" };
    string[] values = Array.ConvertAll(names, name => Environment.GetEnvironmentVariable(name) ?? "");
    File.WriteAllLines(Path.Combine(directory, Process.GetCurrentProcess().Id + ".env"), values);
    Thread.Sleep(180000);
  }
}
`);
    powershell(`Add-Type -Path ${quote(source)} -OutputAssembly ${quote(stub)} -OutputType WindowsApplication`);
    const scenarios = [
      ['changed', 'Emulador mudou; preservado'],
      ['devices-error', 'adb devices falhou'],
      ['kill-error', 'Nao foi possivel derrubar o emulador'],
      ['timeout', 'Emulador nao encerrou'],
      ['success', null],
      ['absent', null],
      ['no-real', null],
      ['inactive', null],
      ['no-state', null],
      ['real-live', null],
      ['real-inactive', null],
      ['real-active', null],
      ['real-dead', null],
      ['real-mismatch', null],
      ['real-relaunch-failure', null],
      ['real-stop-failure', null],
      ['real-debug-active', null],
      ['real-debug-relaunch-failure', null],
      ['real-debug-cleanup-failure', 'Emulador mudou; preservado'],
      ['real-debug-user-closed', 'Emulador mudou; preservado'],
    ];
    for (const [scenario, error] of scenarios) {
      await t.test(scenario, async () => {
        const runDir = join(directory, scenario);
        const debugDir = join(runDir, 'debug');
        const releaseDir = join(runDir, 'release');
        mkdirSync(debugDir, { recursive: true });
        mkdirSync(releaseDir);
        const debugExe = join(debugDir, 'pc.exe');
        const releaseExe = join(releaseDir, 'pc.exe');
        copyFileSync(stub, debugExe);
        copyFileSync(stub, releaseExe);
        const statePath = join(runDir, 'emulator.json');
        const realScenario = scenario.startsWith('real-');
        const realDebug = scenario.startsWith('real-debug-');
        const relaunchFailure = scenario.includes('relaunch-failure');
        const restoreReal = realScenario && !['real-dead', 'real-mismatch'].includes(scenario);
        const realExe = realDebug ? debugExe : releaseExe;
        const realDir = realDebug ? debugDir : releaseDir;
        const hasState = !['no-state', 'real-live', 'real-dead', 'real-mismatch', 'real-relaunch-failure', 'real-stop-failure'].includes(scenario);
        const noOp = ['inactive', 'no-state'].includes(scenario) || (realScenario && !realDebug && scenario !== 'real-active');
        const realStatePath = join(runDir, 'real-pc.json');
        const serial = await freeSerial();
        if (hasState) {
          writeFileSync(statePath, JSON.stringify({ active: !['inactive', 'real-inactive'].includes(scenario),
            serial, realWasRunning: !['no-real', 'real-debug-active'].includes(scenario) }));
        }
        const adb = join(runDir, 'adb.ps1');
        writeFileSync(adb, `
$scenario = '${scenario}'
$killed = Join-Path $PSScriptRoot 'killed'
if ($args[0] -eq 'devices') {
  if ($scenario -eq 'devices-error') { exit 1 }
  'List of devices attached'
  if ($scenario -ne 'absent' -and (-not (Test-Path -LiteralPath $killed) -or $scenario -eq 'timeout')) {
    "${serial}\tdevice"
  }
  exit 0
}
if (($args -join ' ') -match 'emu avd name') {
  if ($scenario -in 'changed','real-debug-cleanup-failure','real-debug-user-closed' -and -not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'repaired'))) { 'SomeoneElses_AVD' } else { 'SynchroNice_API37' }
  exit 0
}
if (($args -join ' ') -match 'emu kill') {
  if ($scenario -eq 'kill-error') { exit 1 }
  [IO.File]::WriteAllText($killed, '')
  exit 0
}
exit 0
`);
        const ownedProcesses = `Get-Process pc -ErrorAction SilentlyContinue | Where-Object { $_.Path -in @(${quote(debugExe)}, ${quote(releaseExe)}) }`;
        const processesAt = path => JSON.parse(powershell(`ConvertTo-Json -Compress -InputObject @(Get-Process pc -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq ${quote(path)} } | ForEach-Object { $_.Id })`));
        const wmiFailure = join(runDir, 'wmi-failure');
        const stopFailure = join(runDir, 'stop-failure');
        const wrapper = join(runDir, 'down-wrapper.ps1');
        writeFileSync(wrapper, `
function Invoke-CimMethod {
  param($ClassName, $MethodName, $Arguments)
  if (Test-Path -LiteralPath ${quote(wmiFailure)}) { return @{ ReturnValue=2 } }
  CimCmdlets\\Invoke-CimMethod -ClassName $ClassName -MethodName $MethodName -Arguments $Arguments
}
function Stop-Process {
  [CmdletBinding()]
  param([Parameter(ValueFromPipeline)][Diagnostics.Process]$InputObject, [switch]$Force)
  process {
    if (Test-Path -LiteralPath ${quote(stopFailure)}) { throw 'simulated stop failure' }
    Microsoft.PowerShell.Management\\Stop-Process -InputObject $InputObject -Force:$Force
  }
}
& ${quote(resolve('qa/down.ps1'))} -RunDir ${quote(runDir)} -Adb ${quote(adb)} -DebugExe ${quote(debugExe)} -ReleaseExe ${quote(releaseExe)} -RealPcStatePath ${quote(realStatePath)}
exit $LASTEXITCODE
`);
        const run = () => spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', wrapper], {
          encoding: 'utf8', timeout: 45000, env: { ...process.env,
            SYNCHRONICE_DATA_DIR: 'isolated-data', WEBVIEW2_USER_DATA_FOLDER: 'isolated-profile',
            WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS: '--remote-debugging-port=9999' },
        });
        try {
          const debugPid = Number(powershell(`(Start-Process -FilePath ${quote(debugExe)} -WindowStyle Hidden -PassThru).Id`));
          assert.deepEqual(processesAt(debugExe), [debugPid]);
          let originalRealPid;
          if (realScenario) {
            originalRealPid = Number(powershell(`(Start-Process -FilePath ${quote(realExe)} -WindowStyle Hidden -PassThru).Id`));
            powershell(`$deadline = (Get-Date).AddSeconds(5); while (-not (Test-Path -LiteralPath ${quote(join(realDir, originalRealPid + '.env'))})) { if ((Get-Date) -gt $deadline) { throw 'initial trace missing' }; Start-Sleep -Milliseconds 50 }`);
            if (scenario === 'real-dead') powershell(`$p = Get-Process -Id ${originalRealPid}; $p | Stop-Process -Force; $p.WaitForExit()`);
            rmSync(join(realDir, originalRealPid + '.env'));
            writeFileSync(realStatePath, JSON.stringify({ pid: scenario === 'real-mismatch' ? debugPid : originalRealPid, exe: realExe }));
          }
          if (scenario === 'real-stop-failure') {
            writeFileSync(stopFailure, '');
            const failed = run();
            assert.ifError(failed.error);
            assert.equal(failed.status, 1, failed.stdout + failed.stderr);
            assert.ok(failed.stderr.includes('simulated stop failure'), failed.stderr);
            assert.deepEqual(processesAt(realExe), [originalRealPid], 'failed stop must not launch a second real PC');
            assert.equal(JSON.parse(readFileSync(realStatePath, 'utf8').replace(/^\uFEFF/, '')).restorePending, true);
            rmSync(stopFailure);
          }
          if (relaunchFailure) {
            writeFileSync(wmiFailure, '');
            const failed = run();
            assert.ifError(failed.error);
            assert.equal(failed.status, 1, failed.stdout + failed.stderr);
            assert.ok(failed.stderr.includes('PC real nao reabriu'), failed.stderr);
            assert.ok(existsSync(realStatePath), 'failed relaunch must retain restoration intent');
            assert.ok(!processesAt(realExe).includes(originalRealPid), 'the recorded CDP process has stopped');
            rmSync(wmiFailure);
          }
          const result = run();
          assert.ifError(result.error);
          assert.equal(result.status, error ? 1 : 0, result.stdout + result.stderr);
          if (error) assert.ok(result.stderr.includes(error), result.stderr);
          else assert.deepEqual(JSON.parse(result.stdout), { stopped: true, restored: restoreReal || (!noOp && scenario !== 'no-real') });
          assert.equal(existsSync(realStatePath), realDebug && Boolean(error), 'retain restored identity until emulator cleanup succeeds');
          if (!realDebug) assert.deepEqual(processesAt(debugExe), noOp ? [debugPid] : [], 'debug process must stop on failures too');
          // WMI returns before the child has finished starting; wait for its trace.
          if (restoreReal || (!noOp && scenario !== 'no-real')) {
            if (realDebug) rmSync(join(debugDir, debugPid + '.env'), { force: true });
            powershell(`$deadline = (Get-Date).AddSeconds(5); while (-not (Get-ChildItem -LiteralPath ${quote(realDir)} -Filter '*.env')) { if ((Get-Date) -gt $deadline) { throw 'release trace missing' }; Start-Sleep -Milliseconds 50 }`);
            const traces = readdirSync(realDir).filter(name => name.endsWith('.env'));
            assert.equal(traces.length, 1);
            assert.equal(readFileSync(join(realDir, traces[0]), 'utf8').trim(), '', 'real PC must inherit none of the isolated environment');
            const releasePids = processesAt(realExe);
            assert.deepEqual(releasePids, [Number(traces[0].split('.')[0])]);
            if (restoreReal) assert.notEqual(releasePids[0], originalRealPid, 'CDP process must be replaced');
            // The changed-AVD case proves a failed retry neither loses the error nor duplicates the PC.
            if (scenario === 'changed' || scenario === 'success' || restoreReal) {
              const repeated = run();
              assert.ifError(repeated.error);
              assert.equal(repeated.status, result.status, repeated.stdout + repeated.stderr);
              if (error) assert.ok(repeated.stderr.includes(error), repeated.stderr);
              else assert.deepEqual(JSON.parse(repeated.stdout), { stopped: true, restored: false });
              assert.deepEqual(processesAt(realExe), releasePids);
              assert.deepEqual(readdirSync(realDir).filter(name => name.endsWith('.env')), traces);
            }
            if (realDebug) {
              assert.deepEqual(processesAt(releaseExe), [], 'the recorded debug executable takes precedence over emulator release fallback');
              if (error) {
                const userClosed = scenario === 'real-debug-user-closed';
                if (userClosed) powershell(`$p = Get-Process -Id ${releasePids[0]}; $p | Stop-Process -Force; $p.WaitForExit()`);
                writeFileSync(join(runDir, 'repaired'), '');
                const repaired = run();
                assert.equal(repaired.status, 0, repaired.stdout + repaired.stderr);
                const remainingPids = userClosed ? [] : releasePids;
                assert.deepEqual(processesAt(realExe), remainingPids, 'cleanup retry must preserve the restored real debug PC or respect its user closure');
                assert.deepEqual(processesAt(releaseExe), [], 'cleanup retry must not restore the obsolete release fallback');
                assert.deepEqual(readdirSync(realDir).filter(name => name.endsWith('.env')), traces, 'cleanup retry must not add another launch');
                assert.equal(existsSync(realStatePath), false);
                const repeated = run();
                assert.equal(repeated.status, 0, repeated.stdout + repeated.stderr);
                assert.deepEqual(JSON.parse(repeated.stdout), { stopped: true, restored: false });
                assert.deepEqual(processesAt(realExe), remainingPids);
              }
            }
          } else assert.deepEqual(processesAt(releaseExe), scenario === 'real-mismatch' ? [originalRealPid] : []);
          if (hasState) {
            const state = JSON.parse(readFileSync(statePath, 'utf8').replace(/^\uFEFF/, ''));
            assert.equal(state.active, Boolean(error) && !realDebug);
          }
        } finally {
          powershell(`${ownedProcesses} | ForEach-Object { Stop-Process -Id $_.Id -Force; $_.WaitForExit() }`);
        }
      });
    }
  } finally {
    assert.ok(directory.startsWith(join(tmpdir(), 'clean-020-down-')));
    rmSync(directory, { recursive: true, force: true });
  }
});
