<#
.SYNOPSIS
Writes the text of a release's page: how to download and install it, then what
changed, out of CHANGELOG.md.

.DESCRIPTION
The version is pom.xml's, so the page always describes the files the release
carries, and the version's section of CHANGELOG.md ("## 1.0.0-beta (...)") is
what changed. tools\release\release-notes.md is the part above it, with
{version} standing for the version.

For a tag (GITHUB_REF refs/tags/...) it is strict, because what it writes is
published: the tag has to be "v" and pom.xml's version, and the changelog has
to have a dated section for it. Anywhere else it warns and writes what it can,
so that a branch's Package run exercises it without failing over a version
still in the making.

In a GitHub workflow it also hands the release step the release's name and
whether it is a pre-release: a version with a hyphen in it, like 1.0.0-beta.

.EXAMPLE
pwsh tools\release\release-notes.ps1 -Out target\release\release-notes.md
#>
param(
	[Parameter(Mandatory)][string]$Out
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$tag = $env:GITHUB_REF -like 'refs/tags/*'

function Problem ($text) {
	# An annotation as well: a run's log needs a GitHub sign-in to read.
	if ($tag) {
		if ($env:GITHUB_ACTIONS) { Write-Host "::error title=Release notes::$text" }
		Write-Host "ERROR: $text" -ForegroundColor Red
		exit 1
	}

	if ($env:GITHUB_ACTIONS) { Write-Host "::warning title=Release notes::$text" }
	Write-Host "WARNING: $text" -ForegroundColor Yellow
}

# GitHub shows a release's page with every line break kept, unlike a file in
# the repository, so the changelog's wrapped paragraphs broke mid-sentence
# (132 times on 1.0.0-beta's page). Each paragraph and list item becomes one
# line; headings, list items, tables, quotes and code blocks keep their own.
function Unwrap ([string[]]$text) {
	$out = New-Object System.Collections.Generic.List[string]
	$fenced = $false
	foreach ($line in $text) {
		if ($line -match '^\s*```') {
			$fenced = -not $fenced
			$out.Add($line)
			continue
		}

		$previous = if ($out.Count) { $out[$out.Count - 1] } else { '' }
		$starts = $fenced -or $line -match '^\s*$' -or $line -match '^\s*(#|[-*+] |\d+\. |\||>)' `
			-or $previous -match '^\s*$' -or $previous -match '^\s*(#|\||```)'
		if ($starts) {
			$out.Add($line)
		} else {
			$out[$out.Count - 1] = $previous.TrimEnd() + ' ' + $line.Trim()
		}
	}

	return , $out.ToArray()
}

$version = ([xml](Get-Content (Join-Path $repo 'pom.xml') -Raw)).project.version
if ($tag -and $env:GITHUB_REF_NAME -ne "v$version") {
	Problem "The tag $env:GITHUB_REF_NAME does not name the version in pom.xml ($version), so its release would carry files called EternityKeeper-$version-win64 under another name."
}

# The version's section: from its "## <version>" heading to the next "## ".
$lines = @(Get-Content (Join-Path $repo 'CHANGELOG.md') -Encoding utf8)
$start = -1
for ($i = 0; $i -lt $lines.Count; $i++) {
	if ($lines[$i] -match "^## $([regex]::Escape($version))(\s|$)") { $start = $i; break }
}

$changes = ''
if ($start -lt 0) {
	Problem "CHANGELOG.md has no section for $version (a line starting '## $version')."
} else {
	$end = $lines.Count
	for ($i = $start + 1; $i -lt $lines.Count; $i++) {
		if ($lines[$i] -match '^## ') { $end = $i; break }
	}

	$changes = ((Unwrap $lines[$start..($end - 1)]) -join "`n").TrimEnd()
	if ($lines[$start] -match '\(unreleased\)') {
		Problem "CHANGELOG.md still calls $version unreleased: put the release date in its heading."
	}
}

$intro = (Get-Content (Join-Path $PSScriptRoot 'release-notes.md') -Raw -Encoding utf8).Replace('{version}', $version).TrimEnd()
$text = $intro + "`n"
if ($changes) { $text += "`n---`n`n" + $changes + "`n" }

$path = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Out)
New-Item -ItemType Directory -Force (Split-Path $path -Parent) | Out-Null
[IO.File]::WriteAllText($path, $text, [Text.UTF8Encoding]::new($false))

$name = "Eternity Keeper $version"
$prerelease = if ($version.Contains('-')) { 'true' } else { 'false' }
if ($env:GITHUB_OUTPUT) {
	Add-Content -Path $env:GITHUB_OUTPUT -Encoding utf8 -Value "name=$name", "prerelease=$prerelease"
}

Write-Host "==> $name$(if ($prerelease -eq 'true') { ', a pre-release' }): $path, $($text.Length) characters"
