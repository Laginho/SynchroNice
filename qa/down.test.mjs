import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, readdirSync, copyFileSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const quote = value => `'${value.replaceAll("'", "''")}'`;
function powershell(command) {
  const result = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command',
    `$ErrorActionPreference = 'Stop'; ${command}; exit 0`], { encoding: 'utf8', timeout: 30000 });
  assert.ifError(result.error);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  return result.stdout.trim();
}

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
      ['real-debug-active', null],
      ['real-debug-relaunch-failure', null],
      ['real-debug-cleanup-failure', 'Emulador mudou; preservado'],
    ];
    for (const [scenario, error] of scenarios) {
      await t.test(scenario, () => {
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
        const hasState = !['no-state', 'real-live', 'real-dead', 'real-mismatch', 'real-relaunch-failure'].includes(scenario);
        const noOp = ['inactive', 'no-state'].includes(scenario) || (realScenario && !realDebug && scenario !== 'real-active');
        const realStatePath = join(runDir, 'real-pc.json');
        if (hasState) {
          writeFileSync(statePath, JSON.stringify({ active: !['inactive', 'real-inactive'].includes(scenario),
            serial: 'emulator-5554', realWasRunning: !['no-real', 'real-debug-active'].includes(scenario) }));
        }
        const adb = join(runDir, 'adb.ps1');
        writeFileSync(adb, `
$scenario = '${scenario}'
$killed = Join-Path $PSScriptRoot 'killed'
if ($args[0] -eq 'devices') {
  if ($scenario -eq 'devices-error') { exit 1 }
  'List of devices attached'
  if ($scenario -ne 'absent' -and (-not (Test-Path -LiteralPath $killed) -or $scenario -eq 'timeout')) {
    "emulator-5554\tdevice"
  }
  exit 0
}
if (($args -join ' ') -match 'emu avd name') {
  if ($scenario -in 'changed','real-debug-cleanup-failure' -and -not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'repaired'))) { 'SomeoneElses_AVD' } else { 'SynchroNice_API37' }
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
        const wrapper = join(runDir, 'down-wrapper.ps1');
        writeFileSync(wrapper, `
function Invoke-CimMethod {
  param($ClassName, $MethodName, $Arguments)
  if (Test-Path -LiteralPath ${quote(wmiFailure)}) { return @{ ReturnValue=2 } }
  CimCmdlets\\Invoke-CimMethod -ClassName $ClassName -MethodName $MethodName -Arguments $Arguments
}
& ${quote(resolve('qa/down.ps1'))} -RunDir ${quote(runDir)} -Adb ${quote(adb)} -DebugExe ${quote(debugExe)} -ReleaseExe ${quote(releaseExe)} -RealPcStatePath ${quote(realStatePath)}
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
                writeFileSync(join(runDir, 'repaired'), '');
                const repaired = run();
                assert.equal(repaired.status, 0, repaired.stdout + repaired.stderr);
                assert.deepEqual(processesAt(realExe), releasePids, 'cleanup retry must preserve the restored real debug PC');
                assert.equal(existsSync(realStatePath), false);
                const repeated = run();
                assert.equal(repeated.status, 0, repeated.stdout + repeated.stderr);
                assert.deepEqual(JSON.parse(repeated.stdout), { stopped: true, restored: false });
                assert.deepEqual(processesAt(realExe), releasePids);
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
