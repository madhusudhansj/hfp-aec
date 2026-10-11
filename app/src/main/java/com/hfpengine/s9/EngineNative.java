package com.hfpengine.s9;

final class EngineNative {
    static { System.loadLibrary("hfp_audio"); }
    private EngineNative() {}
    static native String start(String wavPath, String speexLibraryPath,
                               int micDeviceId, int outputDeviceId, int preset, int channels);
    static native void stop();
    // 0 = silent, 1 = WAV speech loop, 2 = quiet 660-Hz diagnostic tone.
    static native void setPlayback(int mode);
    // 0 = bypass, 1 = WebRTC APM, 2 = optional Speex.
    static native void config(int engineMode, int noiseSuppression, boolean agc, int delayMs);
    static native boolean record();
    static native String export(String prefix);
    static native String stats();
}
