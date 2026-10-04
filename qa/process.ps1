function Stop-AndWait {
    param([Parameter(ValueFromPipeline)][Diagnostics.Process]$Process)
    process {
        $timedOut = $false
        try {
            if (-not $Process.HasExited) {
                # Keep the handle so waiting cannot attach to a reused PID.
                $null = $Process.Handle
                $Process | Stop-Process -Force -ErrorAction Stop
                if (-not $Process.WaitForExit(10000)) {
                    $timedOut = $true
                    throw 'nao encerrou em 10 segundos'
                }
            }
        } catch {
            if ($timedOut -or -not $Process.HasExited) { throw "infraestrutura: PID $($Process.Id): $($_.Exception.Message)" }
        }
    }
}
