param()

$ErrorActionPreference = "Stop"
$PROJECT_DIR = $PSScriptRoot

# Refresh PATH in case cmake was just installed
$env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")

Write-Host "===== YinYue Local Model Build =====" -ForegroundColor Cyan

# 1. Check NDK
if (-not (Test-Path env:ANDROID_NDK_HOME)) {
    $ndkDirs = @(
        "$env:LOCALAPPDATA\Android\Sdk\ndk\27.0.12077973",
        "$env:LOCALAPPDATA\Android\Sdk\ndk\26.3.11579264"
    )
    $found = $false
    foreach ($dir in $ndkDirs) {
        if (Test-Path "$dir\build\cmake\android.toolchain.cmake") {
            $env:ANDROID_NDK_HOME = $dir
            Write-Host "Found NDK: $dir" -ForegroundColor Green
            $found = $true
            break
        }
    }
    if (-not $found) {
        Write-Host "ERROR: Android NDK 27+ not found. Install via Android Studio SDK Manager."
        exit 1
    }
}

# 2. Clone llama.cpp
$LLAMA_DIR = Join-Path $PROJECT_DIR "llama.cpp"
if (-not (Test-Path $LLAMA_DIR)) {
    Write-Host "Cloning llama.cpp ..." -ForegroundColor Yellow
    git clone --depth 1 --branch master https://github.com/ggerganov/llama.cpp.git $LLAMA_DIR
    if ($LASTEXITCODE -ne 0) { exit 1 }
}
else {
    Write-Host "llama.cpp already exists" -ForegroundColor Green
}

# 3. Build dir
$BUILD_DIR = Join-Path $LLAMA_DIR "build-android-arm64"
if (-not (Test-Path $BUILD_DIR)) {
    New-Item -ItemType Directory -Force -Path $BUILD_DIR | Out-Null
}

# 4. CMake configure
Write-Host "Configuring CMake (arm64-v8a)..." -ForegroundColor Yellow

$toolchain = Join-Path $env:ANDROID_NDK_HOME "build\cmake\android.toolchain.cmake"

$cmakeArgs = @(
    "-G", "Unix Makefiles",
    "-S", $LLAMA_DIR,
    "-B", $BUILD_DIR,
    "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
    "-DCMAKE_MAKE_PROGRAM=$env:ANDROID_NDK_HOME\prebuilt\windows-x86_64\bin\make.exe",
    "-DANDROID_ABI=arm64-v8a",
    "-DANDROID_PLATFORM=android-26",
    "-DCMAKE_BUILD_TYPE=Release",
    "-DGGML_OPENMP=OFF",
    "-DGGML_OPENCL=OFF",
    "-DGGML_CUDA=OFF",
    "-DGGML_VULKAN=OFF",
    "-DGGML_METAL=OFF",
    "-DBUILD_SHARED_LIBS=ON",
    "-DGGML_NATIVE=OFF",
    "-DLLAMA_BUILD_TESTS=OFF",
    "-DLLAMA_BUILD_EXAMPLES=OFF",
    "-DLLAMA_BUILD_SERVER=OFF"
)

& cmake @cmakeArgs
if ($LASTEXITCODE -ne 0) { exit 1 }

# 5. Build (only shared libraries, skip executables)
Write-Host "Building ggml + llama shared libs..." -ForegroundColor Yellow
& cmake --build $BUILD_DIR --config Release --target ggml llama -j $env:NUMBER_OF_PROCESSORS
if ($LASTEXITCODE -ne 0) { exit 1 }

# 6. Copy .so files
$JNILIBS = Join-Path $PROJECT_DIR "app\src\main\jniLibs\arm64-v8a"
if (-not (Test-Path $JNILIBS)) {
    New-Item -ItemType Directory -Force -Path $JNILIBS | Out-Null
}

Copy-Item "$BUILD_DIR\bin\*.so" $JNILIBS -Force -ErrorAction SilentlyContinue
Write-Host "Done. .so files copied to app/src/main/jniLibs/arm64-v8a/" -ForegroundColor Green

Write-Host ""
Write-Host "=== Build complete ===" -ForegroundColor Cyan
Write-Host "Next: Open project in Android Studio, Build -> Make Project."
