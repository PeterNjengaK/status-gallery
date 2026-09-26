param(
    [string]$SdkPath = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }),
    [string]$JdkPath = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Android\Android Studio\jbr' })
)
$ErrorActionPreference = 'Stop'
$sdk = $SdkPath
$jdk = $JdkPath
$bt = Join-Path $sdk 'build-tools\36.0.0'
$platform = Join-Path $sdk 'platforms\android-36\android.jar'
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force build, build/classes, build/dex | Out-Null
function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable failed with exit code $LASTEXITCODE" }
}
Invoke-Checked "$jdk\bin\javac.exe" @('-encoding','UTF-8','-source','8','-target','8','-classpath',$platform,'-d','build/classes','src/com/statusprobe/app/MainActivity.java')
Invoke-Checked "$jdk\bin\jar.exe" @('cf','build/classes.jar','-C','build/classes','.')
Invoke-Checked "$jdk\bin\java.exe" @('-cp',"$bt\lib\d8.jar",'com.android.tools.r8.D8','--lib',$platform,'--min-api','26','--output','build/dex','build/classes.jar')
Invoke-Checked "$bt\aapt2.exe" @('compile','--dir','res','-o','build/resources.zip')
Invoke-Checked "$bt\aapt2.exe" @('link','-o','build/unsigned.apk','-I',$platform,'--manifest','AndroidManifest.xml','build/resources.zip')
Invoke-Checked "$jdk\bin\jar.exe" @('uf','build/unsigned.apk','-C','build/dex','classes.dex')
Invoke-Checked "$bt\zipalign.exe" @('-f','4','build/unsigned.apk','build/aligned.apk')
if (!(Test-Path 'build/debug.keystore')) {
    Invoke-Checked "$jdk\bin\keytool.exe" @('-genkeypair','-keystore','build/debug.keystore','-storepass','android','-keypass','android','-alias','androiddebugkey','-dname','CN=Status Probe Debug','-keyalg','RSA','-validity','10000')
}
Invoke-Checked "$jdk\bin\java.exe" @('-jar',"$bt\lib\apksigner.jar",'sign','--ks','build/debug.keystore','--ks-pass','pass:android','--out','build/status-probe.apk','build/aligned.apk')
Invoke-Checked "$jdk\bin\java.exe" @('-jar',"$bt\lib\apksigner.jar",'verify','--verbose','build/status-probe.apk')
