param()

$ErrorActionPreference = "Stop"
$PROJECT_DIR = $PSScriptRoot
$MNN_DIR = Join-Path $PROJECT_DIR "MNN"

$NDK_HOME = "$env:LOCALAPPDATA\Android\Sdk\ndk\27.0.12077973"
if (-not (Test-Path "$NDK_HOME\build\cmake\android.toolchain.cmake")) {
    Write-Host "ERROR: NDK 27 not found at $NDK_HOME" -ForegroundColor Red
    exit 1
}

# Clean previous MNN build
$OLD_BUILD = Join-Path $MNN_DIR "build-android-arm64"
if (Test-Path $OLD_BUILD) {
    Remove-Item -Recurse -Force $OLD_BUILD
    Write-Host "Cleaned previous build" -ForegroundColor Yellow
}

$BUILD_DIR = Join-Path $MNN_DIR "build-android-arm64"
New-Item -ItemType Directory -Force -Path $BUILD_DIR | Out-Null

Write-Host "===== MNN Build for Android (arm64-v8a + LLM engine) =====" -ForegroundColor Cyan

$toolchain = "$NDK_HOME\build\cmake\android.toolchain.cmake"

$cmakeArgs = @(
    "-G", "Unix Makefiles",
    "-S", $MNN_DIR,
    "-B", $BUILD_DIR,
    "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
    "-DCMAKE_MAKE_PROGRAM=$NDK_HOME\prebuilt\windows-x86_64\bin\make.exe",
    "-DANDROID_ABI=arm64-v8a",
    "-DANDROID_PLATFORM=android-26",
    "-DANDROID_STL=c++_shared",
    "-DCMAKE_BUILD_TYPE=Release",
    "-DMNN_OPENCL=ON",
    "-DMNN_USE_LOGCAT=false",
    "-DMNN_BUILD_BENCHMARK=OFF",
    "-DMNN_BUILD_TEST=OFF",
    "-DMNN_USE_SSE=OFF",
    "-DMNN_LOW_MEMORY=ON",
    "-DMNN_BUILD_FOR_ANDROID_COMMAND=true",
    "-DMNN_BUILD_OPENCV=ON",
    "-DMNN_IMGCODECS=ON",
    "-DMNN_SEP_BUILD=ON",
    "-DMNN_SUPPORT_TRANSFORMER_FUSE=ON",
    "-DBUILD_SHARED_LIBS=ON",
    "-DMNN_BUILD_SHARED_LIBS=ON",
    "-DMNN_ARM82=ON",
    "-DMNN_LLM_BUILD_DEMO=OFF"
)

Write-Host "Configuring CMake..."
& cmake @cmakeArgs 2>&1 | Select-Object -Last 5
if ($LASTEXITCODE -ne 0) { exit 1 }

# Build MNN core
Write-Host "Building MNN..." -ForegroundColor Yellow
& cmake --build $BUILD_DIR --config Release --target MNN -j $env:NUMBER_OF_PROCESSORS 2>&1 | Select-Object -Last 3
if ($LASTEXITCODE -ne 0) { exit 1 }

# Build LLM transformer engine
Write-Host "Building LLM engine..." -ForegroundColor Yellow
& cmake --build $BUILD_DIR --config Release --target llm -j $env:NUMBER_OF_PROCESSORS 2>&1 | Select-Object -Last 3
if ($LASTEXITCODE -ne 0) {
    Write-Host "Retry single-thread..." -ForegroundColor Yellow
    & cmake --build $BUILD_DIR --config Release --target llm -j 1 2>&1 | Select-Object -Last 5
    if ($LASTEXITCODE -ne 0) { exit 1 }
}

# Copy .so files
$JNILIBS = Join-Path $PROJECT_DIR "app\src\main\jniLibs\arm64-v8a"

$mnnSo = Get-ChildItem $BUILD_DIR -Recurse -Filter "libMNN.so" | Select-Object -First 1
if ($mnnSo) {
    Copy-Item $mnnSo.FullName "$JNILIBS\libMNN.so" -Force
    Write-Host "Copied libMNN.so ($([math]::Round($mnnSo.Length/1MB, 1))MB)" -ForegroundColor Green
}

$llmSo = Get-ChildItem $BUILD_DIR -Recurse -Filter "libllm.so" | Select-Object -First 1
if ($llmSo) {
    Copy-Item $llmSo.FullName "$JNILIBS\libllm.so" -Force
    Write-Host "Copied libllm.so ($([math]::Round($llmSo.Length/1MB, 1))MB)" -ForegroundColor Green
} else {
    Write-Host "WARNING: libllm.so not found" -ForegroundColor Red
}

$clSo = Get-ChildItem $BUILD_DIR -Recurse -Filter "libMNN_CL.so" | Select-Object -First 1
if ($clSo) {
    Copy-Item $clSo.FullName "$JNILIBS\libMNN_CL.so" -Force
    Write-Host "Copied libMNN_CL.so" -ForegroundColor Green
}

$cppShared = Get-ChildItem "$NDK_HOME\toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\lib\aarch64-linux-android" -Filter "libc++_shared.so" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($cppShared) {
    Copy-Item $cppShared.FullName "$JNILIBS\libc++_shared.so" -Force
    Write-Host "Copied libc++_shared.so" -ForegroundColor Green
}

Write-Host ""
Write-Host "=== MNN + LLM build complete ===" -ForegroundColor Cyan

