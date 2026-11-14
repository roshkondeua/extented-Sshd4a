<#
.SYNOPSIS
    Запуск/остановка SSH-сервера Sshd4a extented на телефоне через adb с перебором адресов.

.DESCRIPTION
    start   отправляет на телефон команду START и ждёт, пока SSH-сервер начнёт отвечать;
            затем перебирает адреса и сообщает, на каком(их) он доступен.
    stop    отправляет STOP и ждёт, пока сервер перестанет отвечать на всех адресах.
    status  только проверяет адреса (adb нужен лишь для автоопределения адресов телефона).
    scan    то же, что status.

    Адреса для перебора берутся из двух источников:
      1) файл sshd4a-targets.txt рядом со скриптом (локальная сеть, ZeroTier, FRP на VDS и т. д.);
      2) адреса самого телефона, которые скрипт узнаёт через adb (wlan0, zt*, и т. п.).
    Порт по умолчанию читается с телефона (файл etc/sshd-port), его можно задать ключом -Port.
    Адрес считается рабочим, только если в ответ на подключение пришёл баннер "SSH-..."
    (FRP принимает соединение, даже когда сервер на телефоне выключен).

.EXAMPLE
    sshd4a-ctl.bat start
    sshd4a-ctl.bat stop
    sshd4a-ctl.bat status -Device R58M123ABC
    sshd4a-ctl.bat start -Port 2222 -Wait 40
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('start', 'stop', 'status', 'scan', 'help')]
    [string]$Action = 'help',
    # серийный номер устройства для adb -s (если подключено несколько)
    [string]$Device = '',
    # файл со списком адресов (по умолчанию sshd4a-targets.txt рядом со скриптом)
    [string]$Targets = '',
    # порт SSH; 0 = прочитать с телефона, иначе 22222
    [int]$Port = 0,
    # сколько секунд ждать результата start/stop
    [int]$Wait = 25,
    # таймаут проверки одного адреса, секунд
    [int]$ProbeTimeout = 2,
    # не вызывать adb (только проверить адреса из файла)
    [switch]$NoAdb
)

$ErrorActionPreference = 'Continue'
$Pkg = 'com.roshkond.sshd4a.extended'
$Activity = "$Pkg/com.hardbacknutter.sshd.QuickStartActivity"
$ActionBase = 'com.hardbacknutter.sshd.fg'
$DeviceBase = '/data/local/tmp/Sshd4a'
if (-not $Targets) { $Targets = Join-Path $PSScriptRoot 'sshd4a-targets.txt' }

function Show-Help {
    Write-Host 'Использование: sshd4a-ctl.bat <start|stop|status|scan> [-Device серийник] [-Port N] [-Wait сек] [-NoAdb]'
    Write-Host ''
    Write-Host '  start   запустить SSH-сервер и показать, на каком адресе он отвечает'
    Write-Host '  stop    остановить SSH-сервер (активные сессии завершаются) и проверить остановку'
    Write-Host '  status  проверить адреса, ничего не меняя'
    Write-Host ''
    Write-Host "Список адресов: $Targets"
    Write-Host '  строки вида   имя = хост[:порт]   (# - комментарий); адреса телефона добавляются автоматически через adb.'
    Write-Host 'На телефоне должна быть включена опция "Allow starting the service by an external Intent".'
}

function Test-AdbPresent {
    if ($NoAdb) { return $true }
    if (Get-Command adb -ErrorAction SilentlyContinue) { return $true }
    Write-Host 'adb не найден в PATH. Установите Android platform-tools или используйте -NoAdb.' -ForegroundColor Red
    return $false
}

function Invoke-Adb {
    param([string[]]$AdbArgs)
    $all = @()
    if ($Device) { $all += @('-s', $Device) }
    $all += $AdbArgs
    $out = & adb @all 2>&1
    $code = $LASTEXITCODE
    $text = (($out | ForEach-Object { "$_" }) -join "`n").Trim()
    return @{ Code = $code; Text = $text }
}

function Get-DevicePort {
    if ($Port -gt 0) { return $Port }
    if (-not $NoAdb) {
        $r = Invoke-Adb @('shell', "cat $DeviceBase/etc/sshd-port")
        if ($r.Code -eq 0 -and $r.Text -match '^\s*(\d{1,5})\s*$') { return [int]$Matches[1] }
    }
    return 22222
}

function Get-PhoneAddresses {
    $list = @()
    if ($NoAdb) { return $list }
    $r = Invoke-Adb @('shell', 'ip -o -4 addr show')
    if ($r.Code -ne 0) { return $list }
    foreach ($line in ($r.Text -split "`n")) {
        if ($line -match '^\d+:\s+(\S+)\s+inet\s+(\d+\.\d+\.\d+\.\d+)/') {
            $iface = $Matches[1]
            $ip = $Matches[2]
            if ($ip.StartsWith('127.')) { continue }
            $list += [pscustomobject]@{ Name = "телефон:$iface"; Host = $ip; Port = 0 }
        }
    }
    return $list
}

function Get-ConfiguredTargets {
    param([int]$DefaultPort)
    $list = @()
    if (-not (Test-Path -LiteralPath $Targets)) { return $list }
    foreach ($raw in (Get-Content -LiteralPath $Targets)) {
        $line = $raw.Trim()
        if ($line -eq '' -or $line.StartsWith('#')) { continue }
        $name = ''
        $addr = $line
        $eq = $line.IndexOf('=')
        if ($eq -gt 0) {
            $name = $line.Substring(0, $eq).Trim()
            $addr = $line.Substring($eq + 1).Trim()
        }
        $h = $addr
        $p = $DefaultPort
        $colon = $addr.LastIndexOf(':')
        if ($colon -gt 0 -and $addr.IndexOf(':') -eq $colon) {
            $h = $addr.Substring(0, $colon).Trim()
            $pp = 0
            if ([int]::TryParse($addr.Substring($colon + 1).Trim(), [ref]$pp) -and $pp -gt 0) { $p = $pp }
        }
        if (-not $name) { $name = $h }
        $list += [pscustomobject]@{ Name = $name; Host = $h; Port = $p }
    }
    return $list
}

function Get-Candidates {
    param([int]$DefaultPort)
    $all = @()
    $all += @(Get-ConfiguredTargets -DefaultPort $DefaultPort)
    foreach ($a in (Get-PhoneAddresses)) {
        $all += [pscustomobject]@{ Name = $a.Name; Host = $a.Host; Port = $DefaultPort }
    }
    $seen = @{}
    $result = @()
    foreach ($c in $all) {
        $key = "$($c.Host):$($c.Port)"
        if (-not $seen.ContainsKey($key)) {
            $seen[$key] = $true
            $result += $c
        }
    }
    return $result
}

function Test-SshEndpoint {
    param([string]$HostName, [int]$PortNumber, [int]$TimeoutMs)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $client.BeginConnect($HostName, $PortNumber, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne($TimeoutMs)) { return $null }
        $client.EndConnect($iar)
        $stream = $client.GetStream()
        $stream.ReadTimeout = $TimeoutMs
        $buf = New-Object byte[] 64
        $n = $stream.Read($buf, 0, 64)
        if ($n -gt 0) { return [System.Text.Encoding]::ASCII.GetString($buf, 0, $n).Trim() }
        return $null
    } catch {
        return $null
    } finally {
        $client.Close()
    }
}

# Проверить все адреса один раз; вернуть список тех, что ответили SSH-баннером.
function Find-Working {
    param([object[]]$Candidates, [bool]$Quiet)
    $working = @()
    foreach ($c in $Candidates) {
        $banner = Test-SshEndpoint -HostName $c.Host -PortNumber $c.Port -TimeoutMs ($ProbeTimeout * 1000)
        if ($banner -and $banner.StartsWith('SSH-')) {
            $working += $c
            if (-not $Quiet) {
                Write-Host ('  [ЕСТЬ] {0,-26} {1}:{2}   {3}' -f $c.Name, $c.Host, $c.Port, $banner) -ForegroundColor Green
            }
        } elseif (-not $Quiet) {
            Write-Host ('  [нет ] {0,-26} {1}:{2}' -f $c.Name, $c.Host, $c.Port) -ForegroundColor DarkGray
        }
    }
    return $working
}

# Показать, откуда взяты адреса (файл / телефон по adb), чтобы было видно, что нашлось.
function Show-Sources {
    param([int]$DefaultPort)
    $cfg = @(Get-ConfiguredTargets -DefaultPort $DefaultPort)
    $ph = @(Get-PhoneAddresses)
    Write-Host ('Адреса из файла: {0}; адреса телефона по adb: {1}' -f $cfg.Count, $ph.Count)
    foreach ($a in $ph) { Write-Host ('    {0}  {1}' -f $a.Name, $a.Host) -ForegroundColor DarkGray }
}

function Send-Control {
    param([string]$What)
    $r = Invoke-Adb @('shell', 'am', 'start', '-n', $Activity, '-a', "$ActionBase.$What")
    if ($r.Code -ne 0 -or $r.Text -match 'Error|Exception|does not exist|no devices') {
        Write-Host "Не удалось отправить $What через adb:" -ForegroundColor Red
        Write-Host $r.Text
        return $false
    }
    return $true
}

function Show-ControlLog {
    $r = Invoke-Adb @('shell', 'logcat -d -s ExternalControl')
    if ($r.Code -eq 0 -and $r.Text) {
        Write-Host 'Журнал приложения (logcat, тег ExternalControl), последние строки:'
        ($r.Text -split "`n") | Select-Object -Last 3 | ForEach-Object { Write-Host "  $_" }
    }
}

function Show-Connect {
    param([object[]]$Working)
    Write-Host ''
    Write-Host 'SSH-сервер доступен:' -ForegroundColor Green
    foreach ($w in $Working) {
        Write-Host ('  {0}: ssh -p {1} <пользователь>@{2}' -f $w.Name, $w.Port, $w.Host) -ForegroundColor Green
    }
}

if ($Action -eq 'help') { Show-Help; exit 0 }
if (-not (Test-AdbPresent)) { exit 1 }

$devPort = Get-DevicePort
Write-Host "Порт SSH: $devPort"
Show-Sources -DefaultPort $devPort

if ($Action -eq 'status' -or $Action -eq 'scan') {
    $cands = @(Get-Candidates -DefaultPort $devPort)
    if ($cands.Count -eq 0) {
        Write-Host "Нет адресов для проверки: заполните $Targets или подключите телефон по adb." -ForegroundColor Yellow
        exit 1
    }
    $working = @(Find-Working -Candidates $cands -Quiet $false)
    if ($working.Count -gt 0) { Show-Connect -Working $working; exit 0 }
    Write-Host ''
    Write-Host 'SSH-сервер не отвечает ни на одном адресе.' -ForegroundColor Yellow
    exit 1
}

if ($Action -eq 'start') {
    $already = @(Find-Working -Candidates @(Get-Candidates -DefaultPort $devPort) -Quiet $true)
    if ($already.Count -gt 0) {
        Write-Host 'Сервер уже работает.'
        Show-Connect -Working $already
        exit 0
    }
    Write-Host 'Отправляю START на телефон...'
    if (-not (Send-Control -What 'START')) { exit 1 }
    $deadline = (Get-Date).AddSeconds($Wait)
    do {
        Start-Sleep -Seconds 2
        $cands = @(Get-Candidates -DefaultPort $devPort)
        $working = @(Find-Working -Candidates $cands -Quiet $true)
        if ($working.Count -gt 0) { break }
    } while ((Get-Date) -lt $deadline)
    Write-Host 'Проверка адресов:'
    $working = @(Find-Working -Candidates $cands -Quiet $false)
    if ($working.Count -gt 0) { Show-Connect -Working $working; exit 0 }
    Write-Host ''
    Write-Host "За $Wait с сервер не ответил ни на одном адресе." -ForegroundColor Red
    Write-Host 'Проверьте: включена ли в настройках опция "Allow starting the service by an external Intent",'
    Write-Host 'и доступны ли адреса из этой сети (ZeroTier/FRP/локальная сеть).'
    Show-ControlLog
    exit 1
}

if ($Action -eq 'stop') {
    $cands = @(Get-Candidates -DefaultPort $devPort)
    $before = @(Find-Working -Candidates $cands -Quiet $true)
    if ($before.Count -gt 0) {
        Write-Host 'Сейчас сервер отвечает на:'
        foreach ($b in $before) { Write-Host ('  {0}  {1}:{2}' -f $b.Name, $b.Host, $b.Port) }
    }
    Write-Host 'Отправляю STOP на телефон...'
    if (-not (Send-Control -What 'STOP')) { exit 1 }
    $deadline = (Get-Date).AddSeconds($Wait)
    do {
        Start-Sleep -Seconds 2
        $still = @(Find-Working -Candidates $cands -Quiet $true)
        if ($still.Count -eq 0) { break }
    } while ((Get-Date) -lt $deadline)
    if ($still.Count -eq 0) {
        Write-Host 'SSH-сервер остановлен: ни один адрес не отвечает.' -ForegroundColor Green
        exit 0
    }
    Write-Host 'Сервер всё ещё отвечает на:' -ForegroundColor Red
    foreach ($s in $still) { Write-Host ('  {0}  {1}:{2}' -f $s.Name, $s.Host, $s.Port) }
    Write-Host 'Остановка не удалась: STOP принимается только для сервера, запущенного внешней командой.'
    Show-ControlLog
    exit 1
}
