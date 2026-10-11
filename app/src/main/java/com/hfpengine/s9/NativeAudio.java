package com.hfpengine.s9;

final class NativeAudio {
    static { System.loadLibrary("hfp_audio"); }
    private NativeAudio() {}
    static native String start(int inputDeviceId, int outputDeviceId, int inputPreset);
    static native void stop();
    static native void setTestTone(boolean enabled);
    static native String stats();
    static native String beginAecRecord(int seconds);
    static native String finishAecRecord(String prefix);
    static native void setAecDelay(int delayMs);
    static native String aecStatus();
}
