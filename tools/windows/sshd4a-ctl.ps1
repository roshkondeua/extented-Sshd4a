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
    Адрес считается рабочим, только если в ответ на подключение пришёл баннер "SSH-..."
    (FRP принимает соединение, даже когда сервер на телефоне выключен).

    Если adb не видит телефон, START/STOP отправить нельзя; скрипт всё равно проверит и покажет
    адреса из файла. Какой adb использовать и как к нему подключиться — ключи -AdbPath и -Connect
    или строки adb-path и adb-connect в sshd4a-targets.txt.

.EXAMPLE
    sshd4a-ctl.bat start
    sshd4a-ctl.bat stop
    sshd4a-ctl.bat status -Device R58M123ABC
    sshd4a-ctl.bat start -AdbPath "C:\Tools\platform-tools\adb.exe" -Connect 192.168.1.50:5555
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('start', 'stop', 'status', 'scan', 'help')]
    [string]$Action = 'help',
    # серийный номер устройства для adb -s (если подключено несколько; для Wi-Fi это адрес:порт)
    [string]$Device = '',
    # файл со списком адресов (по умолчанию sshd4a-targets.txt рядом со скриптом)
    [string]$Targets = '',
    # порт SSH; 0 = прочитать с телефона, иначе 22222
    [int]$Port = 0,
    # сколько секунд ждать результата start/stop
    [int]$Wait = 25,
    # таймаут проверки одного адреса, секунд
    [int]$ProbeTimeout = 2,
    # полный путь к adb.exe (например, к тому, что использует программа AYA)
    [string]$AdbPath = '',
    # выполнить "adb connect адрес:порт" перед работой (беспроводная отладка)
    [string]$Connect = '',
    # не вызывать adb (только проверить адреса из файла)
    [switch]$NoAdb
)

$ErrorActionPreference = 'Continue'
$ScriptVersion = '1.2 (2026-11-14)'
$Pkg = 'com.roshkond.sshd4a.extended'
$Activity = "$Pkg/com.hardbacknutter.sshd.QuickStartActivity"
$ActionBase = 'com.hardbacknutter.sshd.fg'
$DeviceBase = '/data/local/tmp/Sshd4a'
$script:Adb = 'adb'
$script:AdbFailed = $false
if (-not $Targets) { $Targets = Join-Path $PSScriptRoot 'sshd4a-targets.txt' }
$ReservedKeys = @('adb-path', 'adb-connect')

function Show-Help {
    Write-Host 'Использование: sshd4a-ctl.bat <start|stop|status|scan> [-Device серийник] [-Port N] [-Wait сек] [-AdbPath adb.exe] [-Connect адрес:порт] [-NoAdb]'
    Write-Host ''
    Write-Host '  start   запустить SSH-сервер и показать, на каком адресе он отвечает'
    Write-Host '  stop    остановить SSH-сервер (активные сессии завершаются) и проверить остановку'
    Write-Host '  status  проверить адреса, ничего не меняя'
    Write-Host ''
    Write-Host "Список адресов: $Targets"
    Write-Host '  строки вида   имя = хост[:порт]   (# - комментарий); адреса телефона добавляются автоматически через adb.'
    Write-Host '  служебные строки:   adb-path = C:\путь\adb.exe     adb-connect = 192.168.1.50:5555'
    Write-Host 'На телефоне должна быть включена опция "Allow starting the service by an external Intent".'
}

# Значение служебной строки "ключ = значение" из файла адресов ('' если нет).
function Get-FileSetting {
    param([string]$Key)
    if (-not (Test-Path -LiteralPath $Targets)) { return '' }
    foreach ($raw in (Get-Content -LiteralPath $Targets)) {
        $line = $raw.Trim()
        if ($line -eq '' -or $line.StartsWith('#')) { continue }
        $eq = $line.IndexOf('=')
        if ($eq -le 0) { continue }
        if ($line.Substring(0, $eq).Trim() -ieq $Key) { return $line.Substring($eq + 1).Trim() }
    }
    return ''
}

function Invoke-Adb {
    param([string[]]$AdbArgs, [switch]$NoSerial)
    $all = @()
    if ($Device -and -not $NoSerial) { $all += @('-s', $Device) }
    $all += $AdbArgs
    $out = & $script:Adb @all 2>&1
    $code = $LASTEXITCODE
    $text = (($out | ForEach-Object { "$_" }) -join "`n").Trim()
    return @{ Code = $code; Text = $text }
}

# Найти adb, при необходимости подключиться по Wi-Fi, убедиться, что есть готовое устройство.
# Возвращает $true, если можно отправлять команды на телефон.
function Initialize-Adb {
    $path = $AdbPath
    if (-not $path -and $env:SSHD4A_ADB) { $path = $env:SSHD4A_ADB }
    if (-not $path) { $path = Get-FileSetting -Key 'adb-path' }
    if ($path) {
        if (-not (Test-Path -LiteralPath $path)) {
            Write-Host "adb не найден по пути: $path" -ForegroundColor Red
            return $false
        }
        $script:Adb = $path
    } else {
        $cmd = Get-Command adb -ErrorAction SilentlyContinue
        if (-not $cmd) {
            Write-Host 'adb не найден в PATH. Установите Android platform-tools, укажите -AdbPath или строку adb-path в файле адресов.' -ForegroundColor Red
            return $false
        }
        $script:Adb = $cmd.Source
    }
    Write-Host "adb: $($script:Adb)"

    $conn = $Connect
    if (-not $conn) { $conn = Get-FileSetting -Key 'adb-connect' }
    if ($conn) {
        $c = Invoke-Adb -AdbArgs @('connect', $conn) -NoSerial
        Write-Host ('adb connect {0}: {1}' -f $conn, $c.Text)
    }

    $r = Invoke-Adb -AdbArgs @('devices', '-l') -NoSerial
    $ready = @()
    $other = @()
    foreach ($line in ($r.Text -split "`n")) {
        if ($line -match '^(\S+)\s+(device|unauthorized|offline|no permissions)\b') {
            if ($Matches[2] -eq 'device') { $ready += $Matches[1] } else { $other += ('{0} ({1})' -f $Matches[1], $Matches[2]) }
        }
    }
    if ($Device) {
        if ($ready -contains $Device) { return $true }
        Write-Host "Устройство '$Device' не найдено среди готовых к работе." -ForegroundColor Red
    } elseif ($ready.Count -eq 1) {
        Write-Host "Устройство: $($ready[0])"
        return $true
    } elseif ($ready.Count -gt 1) {
        Write-Host 'Подключено несколько устройств, укажите нужное ключом -Device:' -ForegroundColor Red
        foreach ($d in $ready) { Write-Host "    $d" }
        return $false
    } else {
        Write-Host 'adb не видит ни одного готового устройства.' -ForegroundColor Red
    }
    Write-Host 'Вывод "adb devices -l":'
    Write-Host $r.Text
    if ($other.Count -gt 0) { Write-Host ('Есть устройства не в рабочем состоянии: ' + ($other -join ', ')) -ForegroundColor Yellow }
    Write-Host 'Возможные причины:'
    Write-Host '  - программа AYA (или другая) запустила СВОЙ adb-сервер другой версии: укажите её adb.exe ключом -AdbPath'
    Write-Host '    (или строкой adb-path в файле адресов), чтобы использовать тот же сервер;'
    Write-Host '  - подключение по Wi-Fi: укажите -Connect адрес:порт (или adb-connect в файле адресов);'
    Write-Host '  - на телефоне не подтверждена отладка по USB/Wi-Fi (статус unauthorized).'
    return $false
}

function Get-DevicePort {
    if ($Port -gt 0) { return $Port }
    if (-not $NoAdb -and -not $script:AdbFailed) {
        $r = Invoke-Adb @('shell', "cat $DeviceBase/etc/sshd-port")
        if ($r.Code -eq 0 -and $r.Text -match '^\s*(\d{1,5})\s*$') { return [int]$Matches[1] }
    }
    return 22222
}

function Get-PhoneAddresses {
    $list = @()
    if ($NoAdb -or $script:AdbFailed) { return $list }
    $r = Invoke-Adb @('shell', 'ip -o -4 addr show')
    if ($r.Code -ne 0) { return $list }
    foreach ($line in ($r.Text -split "`n")) {
        if ($line -match '^\d+:\s+(\S+)\s+inet\s+(\d+\.\d+\.\d+\.\d+)/') {
            $iface = $Matches[1]
            $ip = $Matches[2]
            if ($ip.StartsWith('127.')) { continue }
            $list += [pscustomobject]@{ Name = "телефон:$iface"; Host = $ip; Port = 0; Source = 'phone' }
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
        if ($ReservedKeys -contains $name.ToLower()) { continue }
        $h = $addr
        $p = $DefaultPort
        $colon = $addr.LastIndexOf(':')
        if ($colon -gt 0 -and $addr.IndexOf(':') -eq $colon) {
            $h = $addr.Substring(0, $colon).Trim()
            $pp = 0
            if ([int]::TryParse($addr.Substring($colon + 1).Trim(), [ref]$pp) -and $pp -gt 0) { $p = $pp }
        }
        if (-not $name) { $name = $h }
        $list += [pscustomobject]@{ Name = $name; Host = $h; Port = $p; Source = 'file' }
    }
    return $list
}

function Get-Candidates {
    param([int]$DefaultPort)
    $all = @()
    $all += @(Get-ConfiguredTargets -DefaultPort $DefaultPort)
    foreach ($a in (Get-PhoneAddresses)) {
        $all += [pscustomobject]@{ Name = $a.Name; Host = $a.Host; Port = $DefaultPort; Source = 'phone' }
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

# Откуда взят адрес: из файла sshd4a-targets.txt или это адрес самого телефона (adb).
function Get-SourceLabel {
    param([object]$Candidate)
    if ($Candidate.Source -eq 'file') { return ('из ' + (Split-Path -Leaf $Targets)) }
    return 'адрес телефона (adb)'
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
                Write-Host ('  [ЕСТЬ] {0,-24} {1}:{2}   [{3}]   {4}' -f $c.Name, $c.Host, $c.Port, (Get-SourceLabel -Candidate $c), $banner) -ForegroundColor Green
            }
        } elseif (-not $Quiet) {
            Write-Host ('  [нет ] {0,-24} {1}:{2}   [{3}]' -f $c.Name, $c.Host, $c.Port, (Get-SourceLabel -Candidate $c)) -ForegroundColor DarkGray
        }
    }
    return $working
}

# Показать, какие адреса взяты из файла и с телефона.
function Show-Sources {
    param([int]$DefaultPort)
    $cfg = @(Get-ConfiguredTargets -DefaultPort $DefaultPort)
    $ph = @(Get-PhoneAddresses)
    Write-Host ('Адреса из файла {0}: {1}' -f (Split-Path -Leaf $Targets), $cfg.Count)
    foreach ($a in $cfg) { Write-Host ('    {0,-24} {1}:{2}' -f $a.Name, $a.Host, $a.Port) }
    Write-Host ('Адреса телефона по adb: {0}' -f $ph.Count)
    foreach ($a in $ph) { Write-Host ('    {0,-24} {1}' -f $a.Name, $a.Host) }
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
    Write-Host 'SSH-сервер доступен по адресам:' -ForegroundColor Green
    foreach ($w in $Working) {
        Write-Host ('  {0,-24} ssh -p {1} <пользователь>@{2}   [{3}]' -f $w.Name, $w.Port, $w.Host, (Get-SourceLabel -Candidate $w)) -ForegroundColor Green
    }
    $fromFile = @($Working | Where-Object { $_.Source -eq 'file' })
    if ($fromFile.Count -gt 0) {
        Write-Host ('Из {0} отвечают: {1}' -f (Split-Path -Leaf $Targets), (($fromFile | ForEach-Object { $_.Name }) -join ', ')) -ForegroundColor Green
    } else {
        Write-Host ('Ни один адрес из {0} не отвечает (отвечают только адреса телефона).' -f (Split-Path -Leaf $Targets)) -ForegroundColor Yellow
    }
}

if ($Action -eq 'help') { Show-Help; exit 0 }

Write-Host "sshd4a-ctl $ScriptVersion"

if (-not $NoAdb) {
    if (-not (Initialize-Adb)) { $script:AdbFailed = $true }
}

$devPort = Get-DevicePort
if ($script:AdbFailed -or $NoAdb) {
    Write-Host "Порт SSH: $devPort (по умолчанию или из -Port: с телефона порт прочитать нельзя)"
} else {
    Write-Host "Порт SSH: $devPort"
}
Show-Sources -DefaultPort $devPort

$forceFail = $false
if ($script:AdbFailed -and ($Action -eq 'start' -or $Action -eq 'stop')) {
    Write-Host ''
    Write-Host ('Команду {0} отправить нельзя: нет связи с телефоном по adb.' -f $Action.ToUpper()) -ForegroundColor Red
    Write-Host 'Ниже проверка адресов из файла (вдруг сервер уже запущен):'
    $Action = 'status'
    $forceFail = $true
}

if ($Action -eq 'status' -or $Action -eq 'scan') {
    $cands = @(Get-Candidates -DefaultPort $devPort)
    if ($cands.Count -eq 0) {
        Write-Host "Нет адресов для проверки: заполните $Targets или подключите телефон по adb." -ForegroundColor Yellow
        exit 1
    }
    Write-Host ''
    Write-Host 'Проверка адресов:'
    $working = @(Find-Working -Candidates $cands -Quiet $false)
    if ($working.Count -gt 0) {
        Show-Connect -Working $working
        if ($forceFail) { exit 1 }
        exit 0
    }
    Write-Host ''
    Write-Host 'SSH-сервер не отвечает ни на одном адресе.' -ForegroundColor Yellow
    exit 1
}

if ($Action -eq 'start') {
    $already = @(Find-Working -Candidates @(Get-Candidates -DefaultPort $devPort) -Quiet $true)
    if ($already.Count -gt 0) {
        Write-Host 'Сервер уже отвечает, START не нужен.'
        Show-Connect -Working $already
        exit 0
    }
    Write-Host ''
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
        foreach ($b in $before) { Write-Host ('  {0}  {1}:{2}   [{3}]' -f $b.Name, $b.Host, $b.Port, (Get-SourceLabel -Candidate $b)) }
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
    foreach ($s in $still) { Write-Host ('  {0}  {1}:{2}   [{3}]' -f $s.Name, $s.Host, $s.Port, (Get-SourceLabel -Candidate $s)) }
    Write-Host 'Остановка не удалась: STOP принимается только для сервера, запущенного внешней командой.'
    Show-ControlLog
    exit 1
}
