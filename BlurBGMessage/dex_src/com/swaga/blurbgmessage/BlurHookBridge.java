package com.swaga.blurbgmessage;

import android.graphics.Canvas;
import android.graphics.Paint;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.telegram.ui.ActionBar.MessageDrawable;

public class BlurHookBridge {
    private static final List<XC_MethodHook.Unhook> unhooks = new ArrayList<>();
    private static volatile boolean installed = false;

    public void onLoad(Object context, Object bridge) {
        ClassLoader cl = context != null ? context.getClass().getClassLoader() : getClass().getClassLoader();
        installHooks(cl, bridge);
    }

    public void onUnload() {
        unhookAll();
    }

    public static synchronized boolean installHooks(ClassLoader classLoader) {
        return installHooks(classLoader, null);
    }

    public static synchronized boolean installHooks(ClassLoader classLoader, Object pyBridge) {
        if (pyBridge != null) {
            BlurDrawer.setPyBridge(pyBridge);
        }
        if (installed) {
            BlurDrawer.log("Hooks are already installed");
            return true;
        }
        BlurDrawer.log("Starting installHooks with classLoader: " + classLoader);
        try {
            Class<?> messageDrawableClass = null;
            for (String name : new String[]{
                "org.telegram.ui.ActionBar.MessageDrawable",
                "org.telegram.ui.ActionBar.Theme$MessageDrawable"
            }) {
                try {
                    messageDrawableClass = classLoader.loadClass(name);
                    BlurDrawer.log("Found MessageDrawable class: " + name);
                    break;
                } catch (ClassNotFoundException ignored) {}
            }

            Class<?> chatMessageCellClass = null;
            try {
                chatMessageCellClass = classLoader.loadClass("org.telegram.ui.Cells.ChatMessageCell");
                BlurDrawer.log("Found ChatMessageCell class");
            } catch (ClassNotFoundException ignored) {}

            Class<?> chatActivityClass = null;
            try {
                chatActivityClass = classLoader.loadClass("org.telegram.ui.ChatActivity");
                BlurDrawer.log("Found ChatActivity class");
            } catch (ClassNotFoundException ignored) {}

            // 1. Hook draw(Canvas, Paint) on MessageDrawable
            if (messageDrawableClass != null) {
                int drawPaintCount = 0;
                for (Method m : messageDrawableClass.getDeclaredMethods()) {
                    if (m.getName().equals("draw") && m.getParameterTypes().length == 2 &&
                        m.getParameterTypes()[0] == Canvas.class && m.getParameterTypes()[1] == Paint.class) {
                        try {
                            unhooks.add(XposedBridge.hookMethod(m, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    try {
                                        MessageDrawable drawable = (MessageDrawable) param.thisObject;
                                        Canvas canvas = (Canvas) param.args[0];
                                        Paint paint = (Paint) param.args[1];
                                        if (BlurDrawer.handleDraw(drawable, canvas, paint)) {
                                            param.setResult(null);
                                        }
                                    } catch (Throwable ignored) {}
                                }
                            }));
                            drawPaintCount++;
                        } catch (Throwable ignored) {}
                    }
                }
                BlurDrawer.log("Hooked draw(Canvas, Paint): " + drawPaintCount);
            }

            // 2. Hook ChatMessageCell.drawBackgroundInParent()
            if (chatMessageCellClass != null) {
                try {
                    Method drawBgMethod = chatMessageCellClass.getDeclaredMethod("drawBackgroundInParent");
                    unhooks.add(XposedBridge.hookMethod(drawBgMethod, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (BlurDrawer.handleDrawBackgroundInParent(param.thisObject)) {
                                    param.setResult(Boolean.TRUE);
                                }
                            } catch (Throwable ignored) {}
                        }
                    }));
                    BlurDrawer.log("Hooked ChatMessageCell.drawBackgroundInParent()");
                } catch (Throwable t) {
                    BlurDrawer.log("Failed to hook drawBackgroundInParent: " + t);
                }
            }

            // 3. Hook ChatActivity lifecycle
            if (chatActivityClass != null) {
                try {
                    Method onResumeMethod = chatActivityClass.getDeclaredMethod("onResume");
                    unhooks.add(XposedBridge.hookMethod(onResumeMethod, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object activity = param.thisObject;
                                Object wallpaperSource = BlurDrawer.getFieldSafe(activity, "navbarContentSourceWallpaper");
                                if (wallpaperSource != null) {
                                    BlurDrawer.setSource(wallpaperSource);
                                }
                            } catch (Throwable ignored) {}
                        }
                    }));
                    BlurDrawer.log("Hooked ChatActivity.onResume");
                } catch (Throwable t) {
                    BlurDrawer.log("Failed to hook ChatActivity.onResume: " + t);
                }

                try {
                    Method onDestroyMethod = chatActivityClass.getDeclaredMethod("onFragmentDestroy");
                    unhooks.add(XposedBridge.hookMethod(onDestroyMethod, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object activity = param.thisObject;
                                Object wallpaperSource = BlurDrawer.getFieldSafe(activity, "navbarContentSourceWallpaper");
                                if (wallpaperSource != null) {
                                    BlurDrawer.clearSource(wallpaperSource);
                                }
                            } catch (Throwable ignored) {}
                        }
                    }));
                    BlurDrawer.log("Hooked ChatActivity.onFragmentDestroy");
                } catch (Throwable t) {
                    BlurDrawer.log("Failed to hook ChatActivity.onFragmentDestroy: " + t);
                }
            }

            installed = true;
            BlurDrawer.log("Successfully installed " + unhooks.size() + " hooks!");
            return true;
        } catch (Throwable t) {
            BlurDrawer.log("installHooks fatal error: " + t);
            return false;
        }
    }

    public static synchronized void unhookAll() {
        BlurDrawer.log("Unhooking all " + unhooks.size() + " hooks");
        for (XC_MethodHook.Unhook unhook : unhooks) {
            try {
                unhook.unhook();
            } catch (Throwable ignored) {}
        }
        unhooks.clear();
        BlurDrawer.clearSource(null);
        installed = false;
    }

    public static boolean isInstalled() {
        return installed;
    }
}
