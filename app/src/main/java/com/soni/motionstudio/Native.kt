package com.soni.motionstudio

object Native {
    init { System.loadLibrary("soninative") }
    external fun analyze(pcm: ShortArray, sampleRate: Int, fps: Int, bands: Int): FloatArray
    external fun vulkanInfo(): String
}
