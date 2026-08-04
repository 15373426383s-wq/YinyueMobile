package com.yinyue.mobile.mnn

/** MNN 库加载器,双重检查锁保证单次加载。借鉴 Operit MNNLibraryLoader。 */
object MNNLibraryLoader {
    @Volatile private var loaded = false

    @Synchronized
    fun load(): Boolean {
        if (loaded) return true
        return try {
            MNNLlmNative.ensure()
            loaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }
}
