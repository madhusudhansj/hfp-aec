package com.hfpengine.s9;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

final class AudioDevices {
    static final class Choice {
        final int input, output;
        final String inputName, outputName;
        Choice(int in, int out, String inName, String outName) {
            input=in; output=out; inputName=inName; outputName=outName;
        }
    }
    private AudioDevices() {}

    static boolean isUsb(AudioDeviceInfo d) {
        int t = d.getType();
        return t == AudioDeviceInfo.TYPE_USB_DEVICE ||
                t == AudioDeviceInfo.TYPE_USB_HEADSET ||
                t == AudioDeviceInfo.TYPE_USB_ACCESSORY;
    }
    static int inputRank(AudioDeviceInfo d) {
        if (isUsb(d)) return 100;
        if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) return 50;
        return 10;
    }
    static int outputRank(AudioDeviceInfo d) {
        if (isUsb(d)) return 100;
        if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return 50;
        return 10;
    }
    static String label(AudioDeviceInfo d) {
        String name = String.valueOf(d.getProductName());
        return name + " (id=" + d.getId() + ", type=" + d.getType() + ")";
    }
    static Choice choose(Context ctx) {
        AudioManager manager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo mic = null, speaker = null;
        for (AudioDeviceInfo d : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (mic == null || inputRank(d) > inputRank(mic)) mic=d;
        }
        for (AudioDeviceInfo d : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (speaker == null || outputRank(d) > outputRank(speaker)) speaker=d;
        }
        // AAUDIO_UNSPECIFIED = 0. This is a last fallback to platform routing.
        return new Choice(mic == null ? 0 : mic.getId(),
                speaker == null ? 0 : speaker.getId(),
                mic == null ? "Platform default" : label(mic),
                speaker == null ? "Platform default" : label(speaker));
    }
    static String describe(Context ctx) {
        AudioManager manager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        StringBuilder sb = new StringBuilder("INPUT DEVICES\n");
        for (AudioDeviceInfo d : manager.getDevices(AudioManager.GET_DEVICES_INPUTS))
            sb.append("  ").append(label(d)).append('\n');
        sb.append("OUTPUT DEVICES\n");
        for (AudioDeviceInfo d : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            sb.append("  ").append(label(d)).append('\n');
        return sb.toString();
    }
}
