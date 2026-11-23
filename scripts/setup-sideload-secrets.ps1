<#
.SYNOPSIS
  One-time setup (re-runnable): uploads your Android signing key to the GitHub repository secrets that
  .github/workflows/sideload.yml uses, so GitHub-built APKs are signed with YOUR key and can update the app you
  already have installed.

.DESCRIPTION
  Run this yourself, in PowerShell, once. It needs the GitHub CLI (gh) logged in with permission to set secrets.

  Default: uses your Android debug keystore at $env:USERPROFILE\.android\debug.keystore
  (alias androiddebugkey, store and key password "android") and asks you nothing.

  Other key: pass -KeystorePath and -Alias; you are then asked for the passwords (typed hidden, never echoed).

  The keystore is read into memory, base64-encoded in memory and written straight to gh's standard input. Nothing is
  printed, logged or saved to disk. Passwords are sent the same way. It sets, on the repository:
    SIDELOAD_KEYSTORE_BASE64, SIDELOAD_KEYSTORE_PASSWORD, SIDELOAD_KEY_ALIAS, SIDELOAD_KEY_PASSWORD
  Re-running simply overwrites them with the same values.

  It also prints the certificate SHA-256 fingerprint (public information) of the key, so you can compare it with the
  "Signing certificate SHA-256" line in a GitHub release's notes after the workflow runs.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\setup-sideload-secrets.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\setup-sideload-secrets.ps1 -KeystorePath D:\keys\my.jks -Alias upload
#>
[CmdletBinding()]
param(
    [string]$KeystorePath = (Join-Path $env:USERPROFILE '.android\debug.keystore'),
    [string]$Alias = 'androiddebugkey',
    [string]$Repo = 'CosmicGrub/GameSuite'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Fail([string]$msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }

function ConvertTo-PlainText([System.Security.SecureString]$s) {
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

# Sends $Value to "gh secret set $Name" on standard input, with no trailing newline and nothing echoed.
function Set-GhSecret([string]$Name, [string]$Value) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $script:GhPath
    $psi.Arguments = "secret set $Name --repo $Repo"
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $p = [System.Diagnostics.Process]::Start($psi)
    $p.StandardInput.Write($Value)
    $p.StandardInput.Close()
    $null = $p.StandardOutput.ReadToEnd()
    $err = $p.StandardError.ReadToEnd()
    $p.WaitForExit()
    if ($p.ExitCode -ne 0) { Fail "gh could not set $Name. $err" }
    Write-Host "  set $Name" -ForegroundColor Green
}

# --- 1. gh present and logged in ------------------------------------------------------------------------------
$gh = Get-Command gh -ErrorAction SilentlyContinue
if (-not $gh) { Fail 'The GitHub CLI (gh) is not installed. Install it from https://cli.github.com/ and run: gh auth login' }
$script:GhPath = $gh.Source
& $script:GhPath auth status *> $null
if ($LASTEXITCODE -ne 0) { Fail 'gh is not logged in. Run: gh auth login   (then run this script again)' }
Write-Host 'gh is logged in.'

# --- 2. keystore ----------------------------------------------------------------------------------------------
if (-not (Test-Path -LiteralPath $KeystorePath -PathType Leaf)) {
    Fail "Keystore not found at $KeystorePath. If you have never built the app on this PC the debug keystore does not exist yet; build once in Android Studio, or pass -KeystorePath and -Alias."
}
$isDefaultDebug = ($Alias -eq 'androiddebugkey') -and ((Split-Path -Leaf $KeystorePath) -eq 'debug.keystore')
if ($isDefaultDebug) {
    $storePass = 'android'
    $keyPass = 'android'
    Write-Host 'Using the default Android debug keystore (no questions asked).'
} else {
    $sp = Read-Host -AsSecureString 'Keystore password (typed hidden)'
    $kp = Read-Host -AsSecureString 'Key password (hidden; press Enter if it is the same as the keystore password)'
    $storePass = ConvertTo-PlainText $sp
    $keyPass = ConvertTo-PlainText $kp
    if ([string]::IsNullOrEmpty($keyPass)) { $keyPass = $storePass }
}

# --- 3. certificate fingerprint (public info) via keytool, password passed through an environment variable --------
$keytool = Get-Command keytool -ErrorAction SilentlyContinue
if (-not $keytool -and $env:JAVA_HOME) {
    $cand = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
    if (Test-Path $cand) { $keytool = Get-Item $cand }
}
if (-not $keytool) {
    foreach ($c in @("$env:ProgramFiles\Android\Android Studio\jbr\bin\keytool.exe", "$env:ProgramFiles\Android\Android Studio\jre\bin\keytool.exe")) {
        if (Test-Path $c) { $keytool = Get-Item $c; break }
    }
}
if ($keytool) {
    $ktPath = if ($keytool -is [System.Management.Automation.ApplicationInfo]) { $keytool.Source } else { $keytool.FullName }
    $env:GS_KT_PASS = $storePass
    try {
        $out = & $ktPath -list -v -keystore $KeystorePath -alias $Alias '-storepass:env' GS_KT_PASS 2>&1
        if ($LASTEXITCODE -ne 0) { Fail "keytool could not open that keystore/alias (wrong alias or password?)." }
        $fp = $out | Where-Object { $_ -match 'SHA-?256:' } | Select-Object -First 1
        if ($fp) {
            Write-Host ''
            Write-Host 'Certificate fingerprint of this key (compare with the release notes after the first build):'
            Write-Host ('  ' + $fp.ToString().Trim()) -ForegroundColor Cyan
        }
    } finally { Remove-Item Env:\GS_KT_PASS -ErrorAction SilentlyContinue }
} else {
    Write-Host 'keytool not found, skipping the fingerprint print (the upload below still works).' -ForegroundColor Yellow
}

# --- 4. upload ---------------------------------------------------------------------------------------------------
Write-Host ''
Write-Host "Setting repository secrets on $Repo (values go straight to gh, nothing is printed)..."
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($KeystorePath))
Set-GhSecret 'SIDELOAD_KEYSTORE_BASE64' $b64
Set-GhSecret 'SIDELOAD_KEYSTORE_PASSWORD' $storePass
Set-GhSecret 'SIDELOAD_KEY_ALIAS' $Alias
Set-GhSecret 'SIDELOAD_KEY_PASSWORD' $keyPass
$b64 = $null; $storePass = $null; $keyPass = $null

# --- 5. next step -----------------------------------------------------------------------------------------------
Write-Host ''
Write-Host 'Done. Next step: run the workflow (or just push a release tag):' -ForegroundColor Green
Write-Host "  gh workflow run sideload.yml --repo $Repo --ref session/wave-3 -f tag=<existing wave3-<versionCode>-<sha> tag>"
Write-Host 'Then on your phone: open GameSuite > Settings > App updates > Update.'
Write-Host 'The workflow can be re-run for the same tag any time; this script is safe to run again.'
