# ===== Yinyue MNN 部署脚本 =====
# 用法：PowerShell 中执行 .\deploy_mnn.ps1
# 前置条件：手机已连接（adb devices 可见）

param()

$ErrorActionPreference = "Stop"
# 使用脚本所在目录，避免硬编码绝对路径泄露本机信息
$PROJECT = $PSScriptRoot
$MODEL_SRC = "$PROJECT\mnn_models\Qwen3-8B-MNN"
# 自动探测 adb：优先 PATH，回退到 Android SDK 默认位置
$ADB = "adb"
$sdkAdb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    if (Test-Path $sdkAdb) { $ADB = $sdkAdb }
    else {
        Write-Host "错误：未找到 adb，请将其加入 PATH 或安装 Android SDK。" -ForegroundColor Red
        exit 1
    }
}
$PKG = "com.yinyue.mobile"
$DEVICE_MNN = "/sdcard/mnn_models"

Write-Host "===== Yinyue MNN 部署 ===== " -ForegroundColor Cyan

# 1. 检查设备
Write-Host "[1/5] 检查设备连接..." -ForegroundColor Yellow
$devices = & $ADB devices 2>&1 | Select-String -Pattern "device$"
if (-not $devices) {
    Write-Host "错误：未检测到设备。请用 USB 连接手机并开启 USB 调试。" -ForegroundColor Red
    exit 1
}
Write-Host "  设备已连接" -ForegroundColor Green

# 2. 推送模型
Write-Host "[2/5] 推送 MNN 模型到手机..." -ForegroundColor Yellow
& $ADB shell "mkdir -p $DEVICE_MNN/Qwen3-8B-MNN"
$files = @(
    "config.json",
    "llm_config.json", 
    "tokenizer.txt",
    "llm.mnn",
    "llm.mnn.json",
    "llm.mnn.weight",
    "embeddings_int4.bin"
)

foreach ($f in $files) {
    $src = "$MODEL_SRC\$f"
    if (-not (Test-Path $src)) {
        Write-Host "  跳过: $f (不存在)" -ForegroundColor DarkYellow
        continue
    }
    $sizeMB = [math]::Round((Get-Item $src).Length / 1MB, 1)
    Write-Host "  推送: $f ($sizeMB MB) ..."
    & $ADB push $src "$DEVICE_MNN/Qwen3-8B-MNN/$f"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  推送失败: $f" -ForegroundColor Red
        exit 1
    }
}

# 拷贝到 app 私有目录
Write-Host "[3/5] 拷贝到 app 私有目录..." -ForegroundColor Yellow
$appDir = "/data/data/$PKG/files/mnn_models"
& $ADB shell "
    mkdir -p $appDir &&
    cp -r $DEVICE_MNN/Qwen3-8B-MNN/* $appDir/ &&
    chmod -R 755 $appDir
"
Write-Host "  模型已部署到: $appDir" -ForegroundColor Green

# 4. 构建 APK（可选）
Write-Host "[4/5] 构建 APK..." -ForegroundColor Yellow
Push-Location $PROJECT
$gradlew = ".\gradlew.bat"
if (-not (Test-Path $gradlew)) {
    Write-Host "  gradlew.bat 不存在，跳过构建" -ForegroundColor DarkYellow
} else {
    & $gradlew assembleDebug --no-daemon 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  构建失败，请检查错误信息" -ForegroundColor Red
    } else {
        $apk = Get-ChildItem "$PROJECT\app\build\outputs\apk\debug" -Filter "*.apk" | Select-Object -First 1
        Write-Host "  APK: $($apk.FullName)" -ForegroundColor Green
    }
}
Pop-Location

# 5. 安装 APK
Write-Host "[5/5] 安装 APK..." -ForegroundColor Yellow
$apkPath = "$PROJECT\app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apkPath) {
    & $ADB install -r $apkPath
    Write-Host "  安装完成" -ForegroundColor Green
} else {
    Write-Host "  APK 不存在，请先手动构建" -ForegroundColor Red
}

Write-Host ""
Write-Host "===== 部署完成 =====" -ForegroundColor Cyan
Write-Host "启动应用: adb shell am start -n $PKG/.ui.MainActivity"
Write-Host "设置 → 后端选择 → MNN（OpenCL GPU 加速）"
