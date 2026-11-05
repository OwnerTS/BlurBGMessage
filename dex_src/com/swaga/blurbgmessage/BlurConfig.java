package com.swaga.blurbgmessage;

public class BlurConfig {
    public static volatile boolean enabled = true;
    public static volatile int transparency = 50; // 50% transparency for clean frosted blur
    public static volatile boolean border = true;

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setTransparency(int value) {
        transparency = Math.max(10, Math.min(100, value));
    }

    public static int getTransparency() {
        return transparency;
    }

    public static void setBorder(boolean value) {
        border = value;
    }

    public static boolean isBorder() {
        return border;
    }
}
