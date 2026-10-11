package com.hfpengine.s9;

import android.os.Build;
import java.util.Arrays;

/** All capture presets supported by AAudio, not privileged call-capture sources. */
final class CapturePresets {
    private static final int[] IDS = {5, 1, 6, 7, 9, 10};
    private static final String[] LABELS = {"Camcorder", "Generic microphone",
            "Voice recognition", "Voice communication", "Unprocessed", "Voice performance"};
    static int[] ids() { return Arrays.copyOf(IDS, Build.VERSION.SDK_INT >= 29 ? 6 : 5); }
    static String[] labels() { return Arrays.copyOf(LABELS, Build.VERSION.SDK_INT >= 29 ? 6 : 5); }
}
