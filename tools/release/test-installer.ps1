<#
.SYNOPSIS
Installs the release the way a player would, starts it, and uninstalls it.

.DESCRIPTION
The installer is the first thing of the editor a new user runs, on a machine
that has none of what the development setup has. So this checks it there: a
silent install for the current user into a folder with a space in its name,
then

    - every part is in place (launcher, jar, pages, browser, Java, reader);
    - the Start menu entry points at the launcher, and Windows lists the
      editor under Installed apps;
    - setup refuses a folder the launcher could not start from (one whose
      name the system's code page cannot spell) and installs nothing;
    - the Java runtime it ships starts, and the game-data reader can decode
      textures;
    - the installed editor starts and draws its page -- asked over the
      embedded browser's DevTools port -- with its settings and log in the
      data folder it was given, not in the install folder;
    - setup run again while that editor is open stops, and leaves it running;
    - setup run again afterwards, as the next version's would be, clears out
      what the program folders held and keeps what the user put beside the
      launcher;
    - the uninstaller removes all of it and leaves the data folder alone.

It installs into, and removes, a folder of its own under %TEMP%. If Eternity
Keeper is already installed for this user it stops rather than touch that:
both would share one entry under Installed apps. With -DefaultFolder it first
installs once with no folder given, checks that setup chose the user's own
Programs folder, and removes that again.

.EXAMPLE
pwsh tools\release\test-installer.ps1 -Setup target\release\EternityKeeper-1.0.0-beta-win64-setup.exe
#>
param(
	[Parameter(Mandatory)][string]$Setup,
	# The embedded browser's DevTools port the installed editor is started with.
	# Left out, a free one is asked of Windows: a fixed number is one more thing
	# that can be taken already, and an editor that cannot have its port starts
	# without DevTools, which from here looks like one that never started.
	[int]$Port = 0,
	# Install, check the files and uninstall, without starting the editor.
	[switch]$SkipLaunch,
	# Also install once with no folder given, to see where setup itself puts the
	# editor: the user's own Programs folder. It is the one step that installs
	# outside the test's own folder, so only when asked for (the release
	# workflow asks).
	[switch]$DefaultFolder
)

$ErrorActionPreference = 'Stop'
$appId = '{6C0E3B7F-6E9B-4B5E-9C61-3E1F6C0C5A41}_is1'
$uninstallKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\$appId"

function Step ($text) { Write-Host "==> $text" -ForegroundColor Cyan }
function Fail ($text) {
	# An annotation as well: a run's log needs a GitHub sign-in to read.
	if ($env:GITHUB_ACTIONS) { Write-Host "::error title=Installer test::$($text -replace "`r?`n", ' ')" }
	Write-Host "ERROR: $text" -ForegroundColor Red
	exit 1
}

function Stop-Installed ($folder) {
	Get-CimInstance Win32_Process |
		Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($folder, [StringComparison]::OrdinalIgnoreCase) } |
		ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

$Setup = (Resolve-Path $Setup -ErrorAction SilentlyContinue).Path
if (-not $Setup) { Fail 'No installer at the path given.' }
if (Test-Path $uninstallKey) {
	Fail "Eternity Keeper is already installed for this user ($((Get-ItemProperty $uninstallKey).InstallLocation)). This test installs and uninstalls its own copy, and would take that entry with it: run it on a machine without one."
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('ek-installer-test-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
$app = Join-Path $root 'Eternity Keeper'
$data = Join-Path $root 'data'
$log = Join-Path $root 'install.log'
New-Item -ItemType Directory -Force $root | Out-Null
$shortcut = Join-Path ([Environment]::GetFolderPath('Programs')) 'Eternity Keeper.lnk'
$parts = 'Eternity Keeper.exe', 'eternity-keeper.jar', 'ui\index.html', 'jre\bin\java.exe',
	'lib\native\win64\libcef.dll', 'lib\native\win64\jcef_helper.exe',
	'gamedata\extract_gamedata.exe', 'README.txt', 'LICENSE', 'unins000.exe'
# A folder no single code page can spell: two scripts in one name. Written as
# character codes so that this file stays plain ASCII.
$unreachable = Join-Path $root (-join [char[]](0x41F, 0x430, 0x43F, 0x43A, 0x430, 0x20, 0x65E5, 0x672C, 0x8A9E))
$chosen = $null
$failed = $null

function Log-Tail { if (Test-Path $log) { (Get-Content $log -Tail 15) -join "`n" } else { '(no log)' } }

# Setup the way a script runs it: no windows, no questions, for this user. It
# is not waited for without limit: a setup that stops at a message box -- which
# a silent one must never show -- would hold a build machine until the job's
# own limit, hours later, and say nothing about why.
function Run-Setup ($folder, $seconds = 900) {
	if (Test-Path $log) { Remove-Item $log -Force }
	$arguments = @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/CURRENTUSER', "/LOG=`"$log`"")
	if ($folder) { $arguments += "/DIR=`"$folder`"" }
	$run = Start-Process $Setup -PassThru -ArgumentList $arguments
	$null = $run.Handle	# or PowerShell cannot read the exit code once it has gone
	if (-not $run.WaitForExit($seconds * 1000)) {
		& taskkill /PID $run.Id /T /F 2>&1 | Out-Null
		throw "Setup had not finished after $seconds seconds. Run silently it must never wait for an answer, so it is probably showing a message box.`n$(Log-Tail)"
	}
	return $run.ExitCode
}

# The uninstaller the way a script runs it, and everything it has to take away.
function Run-Uninstaller ($folder) {
	Start-Process (Join-Path $folder 'unins000.exe') -Wait -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART')
	$deadline = (Get-Date).AddSeconds(90)
	while ((Test-Path $folder) -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 1 }

	if (Test-Path $folder) {
		$left = (Get-ChildItem $folder -Recurse -File | Select-Object -First 10 | ForEach-Object { $_.FullName.Substring($folder.Length + 1) }) -join ', '
		throw "The uninstaller left files behind: $left"
	}
	if (Test-Path $shortcut) { throw 'The uninstaller left the Start menu entry.' }
	if (Test-Path $uninstallKey) { throw 'The uninstaller left the Installed apps entry.' }
}

try {
	# --- Where it goes when nobody says ------------------------------------------
	# With no folder given setup works one out itself (DefaultDir in
	# installer.iss): the user's own Programs folder, which needs no
	# administrator, or the system drive when the launcher could not start from
	# there.
	if ($DefaultFolder) {
		$usual = Join-Path $env:LOCALAPPDATA 'Programs\Eternity Keeper'
		$fallback = Join-Path $env:SystemDrive 'Eternity Keeper'
		foreach ($folder in $usual, $fallback) {
			if (Test-Path $folder) { throw "There is already a folder at $folder, which this check would install into and then remove." }
		}
		Step 'Installing with no folder given'
		$code = Run-Setup $null
		if ($code -ne 0) { throw "The installer exited with $code.`n$(Log-Tail)" }
		$chosen = (Get-ItemProperty $uninstallKey).InstallLocation.TrimEnd('\')
		$plain = $usual -notmatch '[^\x20-\x7E]'
		if ($chosen -ne $usual -and ($plain -or $chosen -ne $fallback)) {
			throw "Left to choose, setup installed into '$chosen' rather than '$usual'."
		}
		foreach ($part in $parts) {
			if (-not (Test-Path (Join-Path $chosen $part))) { throw "Not installed in the folder setup chose: $part" }
		}
		Step "Setup chose $chosen"
		Run-Uninstaller $chosen
	}

	# --- A folder the editor could not start from --------------------------------
	# The launcher and the Java 8 runtime read their own paths in the system's
	# code page, so from a folder that code page cannot spell the editor never
	# starts. Setup has to say so instead of installing there. (With the UTF-8
	# code page every name can be spelled, and there is nothing to refuse.)
	$codePage = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\Nls\CodePage').ACP
	if ($codePage -ne '65001') {
		Step "Asking for a folder that code page $codePage cannot spell"
		$code = Run-Setup $unreachable 60
		if ($code -eq 0 -or (Test-Path $unreachable) -or (Test-Path $uninstallKey)) {
			throw "Setup installed into a folder the launcher cannot start from (it exited with $code).`n$(Log-Tail)"
		}
	}

	# --- Install ---------------------------------------------------------------
	Step "Installing into $app"
	$code = Run-Setup $app
	if ($code -ne 0) { throw "The installer exited with $code.`n$(Log-Tail)" }

	foreach ($part in $parts) {
		if (-not (Test-Path (Join-Path $app $part))) { throw "Not installed: $part" }
	}

	if (-not (Test-Path $shortcut)) { throw "No Start menu entry at $shortcut" }
	$target = (New-Object -ComObject WScript.Shell).CreateShortcut($shortcut).TargetPath
	if ($target -ne (Join-Path $app 'Eternity Keeper.exe')) { throw "The Start menu entry points at '$target'." }

	if (-not (Test-Path $uninstallKey)) { throw 'Windows does not list the editor under Installed apps.' }
	$entry = Get-ItemProperty $uninstallKey
	if ($entry.DisplayName -ne 'Eternity Keeper' -or -not $entry.DisplayVersion -or -not $entry.UninstallString `
			-or $entry.URLInfoAbout -notlike 'https://*' -or $entry.HelpLink -notlike 'https://*/issues') {
		throw "The Installed apps entry is incomplete: $($entry | Out-String)"
	}
	Step "Installed: $($entry.DisplayName) $($entry.DisplayVersion)"

	# --- What it ships runs on its own ------------------------------------------
	$java = & (Join-Path $app 'jre\bin\java.exe') -version 2>&1 | Out-String
	if ($java -notmatch 'version "1\.8\.') { throw "The Java runtime it ships did not start as Java 8:`n$java" }

	$selfTest = & (Join-Path $app 'gamedata\extract_gamedata.exe') --self-test 2>&1 | Out-String
	if ($LASTEXITCODE -ne 0) { throw "The game-data reader it ships cannot decode textures:`n$selfTest" }

	# --- The installed editor starts ----------------------------------------------
	if (-not $SkipLaunch) {
		Step 'Starting the installed editor'
		# Launch4j reads extra JVM options from an .ini beside the launcher; each
		# is passed as written, so one with a path in it is quoted.
		if (-not $Port) {
			$free = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
			$free.Start()
			$Port = $free.LocalEndpoint.Port
			$free.Stop()
		}
		$ini = Join-Path $app 'Eternity Keeper.l4j.ini'
		Set-Content -Path $ini -Encoding ascii -Value @("`"-Dek.data=$data`"", "-Dek.debugPort=$Port")
		Start-Process (Join-Path $app 'Eternity Keeper.exe')

		$page = $null
		$deadline = (Get-Date).AddSeconds(120)
		while (-not $page -and (Get-Date) -lt $deadline) {
			Start-Sleep -Seconds 2
			try {
				$page = Invoke-RestMethod "http://127.0.0.1:$Port/json/list" -TimeoutSec 3 |
					Where-Object { $_.url -like 'file:///*/ui/index.html' } | Select-Object -First 1
			} catch { }
		}

		if (-not $page) {
			# Everything that tells a slow start from a closed editor from a port
			# that was never opened: this failure has been seen once with none of
			# it written down.
			$running = @(Get-CimInstance Win32_Process |
				Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($app, [StringComparison]::OrdinalIgnoreCase) } |
				ForEach-Object { $_.Name })
			$answer = try { (Invoke-WebRequest "http://127.0.0.1:$Port/json/list" -TimeoutSec 5 -UseBasicParsing).Content } catch { "no answer ($($_.Exception.Message))" }
			$tails = foreach ($name in 'eternity.log', 'cef.log') {
				$file = Join-Path $data $name
				if (Test-Path $file) { "--- the end of ${name}:`n" + ((Get-Content $file -Tail 15) -join "`n") } else { "--- no $name was written" }
			}
			throw ("The installed editor did not draw its page within two minutes.`n" +
				"Still running from the install folder: $(if ($running) { $running -join ', ' } else { 'nothing' }).`n" +
				"DevTools port ${Port}: $answer`n" + ($tails -join "`n"))
		}

		Step "The editor drew $($page.url)"
		$expected = ([Uri](Join-Path $app 'ui\index.html')).AbsoluteUri
		if ($page.url -ne $expected) { throw "It loaded its page from '$($page.url)', not from the install folder ($expected)." }
		if (-not (Test-Path (Join-Path $data 'eternity.log'))) { throw "It did not keep its log in the data folder it was given ($data)." }

		# Setup over an open editor would replace the files it is running from.
		# The launcher makes a mutex that lasts for as long as the editor is open
		# (the Java process it starts inherits it; the launcher itself exits) and
		# setup knows its name (AppMutex in installer.iss): it has to stop, not
		# install.
		Step 'Running setup again while the editor is open'
		$code = Run-Setup $app 60
		if ($code -eq 0) { throw 'Setup installed over an open editor instead of asking for it to be closed.' }
		foreach ($part in $parts) {
			if (-not (Test-Path (Join-Path $app $part))) { throw "Setup, refused because the editor is open, still removed $part." }
		}
		$still = $null
		try { $still = Invoke-RestMethod "http://127.0.0.1:$Port/json/list" -TimeoutSec 5 } catch { }
		if (-not $still) { throw 'The open editor did not survive setup being run beside it.' }

		Stop-Installed $app
		Start-Sleep -Seconds 3
		Remove-Item $ini -Force
		foreach ($stray in 'settings.json', 'eternity.log', 'cef.log') {
			if (Test-Path (Join-Path $app $stray)) { throw "The editor wrote $stray into its install folder." }
		}
	}

	# --- An upgrade ---------------------------------------------------------------
	# The next version's setup clears the program folders before it copies its
	# own, so a file this version no longer ships does not stay beside the ones
	# it does. What the user keeps beside the launcher (its options file) stays.
	Step 'Installing over it, as the next version would'
	$stale = 'ui\js\left-by-the-version-before.js', 'lib\left-by-the-version-before.txt',
		'jre\lib\left-by-the-version-before.txt', 'gamedata\left-by-the-version-before.txt'
	foreach ($file in $stale) { Set-Content -Path (Join-Path $app $file) -Value 'stale' -Encoding ascii }
	$options = Join-Path $app 'Eternity Keeper.l4j.ini'
	Set-Content -Path $options -Value '-Xmx1g' -Encoding ascii

	$code = Run-Setup $app
	if ($code -ne 0) { throw "Installing over an earlier install exited with $code.`n$(Log-Tail)" }
	foreach ($file in $stale) {
		if (Test-Path (Join-Path $app $file)) { throw "An upgrade left $file from the version before." }
	}
	foreach ($part in $parts) {
		if (-not (Test-Path (Join-Path $app $part))) { throw "Not there after an upgrade: $part" }
	}
	if (-not (Test-Path $options)) { throw 'An upgrade removed the options file the user keeps beside the launcher.' }
	if ((Get-ItemProperty $uninstallKey).InstallLocation.TrimEnd('\') -ne $app) { throw 'An upgrade moved the Installed apps entry.' }

	# An upgrade never shows the page the folder is chosen on, so the folder is
	# checked once more just before anything is copied.
	if ($codePage -ne '65001') {
		Step 'Asking the upgrade to move to the folder the code page cannot spell'
		$code = Run-Setup $unreachable 60
		if ($code -eq 0 -or (Test-Path $unreachable)) {
			throw "An upgrade installed into a folder the launcher cannot start from (it exited with $code).`n$(Log-Tail)"
		}
		foreach ($part in $parts) {
			if (-not (Test-Path (Join-Path $app $part))) { throw "A refused upgrade still removed $part." }
		}
	}

	# --- Uninstall ---------------------------------------------------------------
	Step 'Uninstalling'
	Run-Uninstaller $app
	if (-not $SkipLaunch -and -not (Test-Path (Join-Path $data 'settings.json')) -and -not (Test-Path (Join-Path $data 'eternity.log'))) {
		throw 'The uninstaller removed the data folder, which holds the user''s settings and backups.'
	}
} catch {
	$failed = $_.Exception.Message
} finally {
	# Whatever happened, nothing of the test's stays installed.
	Stop-Installed $root
	foreach ($folder in $app, $unreachable, $chosen) {
		if ($folder -and (Test-Path (Join-Path $folder 'unins000.exe'))) {
			Start-Process (Join-Path $folder 'unins000.exe') -Wait -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART')
			Start-Sleep -Seconds 5
		}
	}
	Remove-Item $root -Recurse -Force -ErrorAction SilentlyContinue
}

if ($failed) { Fail $failed }
Step 'The installer installs, the editor starts from it, and the uninstaller removes it.'
