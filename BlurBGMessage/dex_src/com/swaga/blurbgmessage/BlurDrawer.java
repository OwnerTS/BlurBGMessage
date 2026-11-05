package com.swaga.blurbgmessage;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.GlassOutlineStyle;
import com.exteragram.messenger.utils.AppUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.MessageDrawable;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;

public class BlurDrawer {
    private static final String TAG = "BlurBGMessage";

    private static volatile Object pyBridge;
    private static volatile Method pyBridgeRunMethod;

    private static volatile Object currentSource;

    private static final Paint glassPaint;
    private static final Paint topStrokePaint;
    private static final Paint bottomStrokePaint;
    private static final Paint fullStrokePaint;
    private static final Paint selectedPaint;

    private static final Matrix matrix;
    private static final Matrix sourceMatrix;
    private static final Matrix canvasMatrix;
    private static final float[] matrixValues = new float[9];

    private static Bitmap lastBitmap;
    private static BitmapShader bitmapShader;
    private static int lastTintColor;
    private static PorterDuffColorFilter tintColorFilter;

    private static Bitmap fallbackBitmap;
    private static Drawable lastWallpaperDrawable;

    // Reflection cache to prevent any IllegalAccessError on private fields
    private static final Map<String, Field> fieldCache = new ConcurrentHashMap<>();
    private static final Map<String, Method> methodCache = new ConcurrentHashMap<>();

    static {
        glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        glassPaint.setFilterBitmap(true);

        topStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        topStrokePaint.setStyle(Paint.Style.STROKE);
        topStrokePaint.setStrokeWidth(AndroidUtilities.dpf2(1.0f));

        bottomStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bottomStrokePaint.setStyle(Paint.Style.STROKE);
        bottomStrokePaint.setStrokeWidth(AndroidUtilities.dpf2(0.6666667f));

        fullStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fullStrokePaint.setStyle(Paint.Style.STROKE);
        fullStrokePaint.setStrokeWidth(AndroidUtilities.dpf2(1.0f));

        selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        matrix = new Matrix();
        sourceMatrix = new Matrix();
        canvasMatrix = new Matrix();
    }

    public static void log(String msg) {
        try {
            Log.e(TAG, msg);
            AppUtils.log(msg);
            FileLog.d("[" + TAG + "] " + msg);
            if (pyBridge != null && pyBridgeRunMethod != null) {
                pyBridgeRunMethod.invoke(pyBridge, msg);
            }
        } catch (Throwable ignored) {}
    }

    public static void setPyBridge(Object bridge) {
        pyBridge = bridge;
        if (bridge != null) {
            try {
                pyBridgeRunMethod = bridge.getClass().getMethod("run", String.class);
            } catch (Throwable ignored) {}
        }
        log("Python log bridge attached: " + bridge);
    }

    public static Field findFieldAnywhere(Class<?> cls, String name) {
        String key = cls.getName() + "#" + name;
        Field cached = fieldCache.get(key);
        if (cached != null) return cached;
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                fieldCache.put(key, f);
                return f;
            } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    public static Method findMethodAnywhere(Class<?> cls, String name, Class<?>... params) {
        StringBuilder sb = new StringBuilder(cls.getName()).append("#").append(name);
        for (Class<?> p : params) sb.append(":").append(p.getName());
        String key = sb.toString();
        Method cached = methodCache.get(key);
        if (cached != null) return cached;
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                methodCache.put(key, m);
                return m;
            } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    public static Object getFieldSafe(Object obj, String name) {
        if (obj == null) return null;
        try {
            Field f = findFieldAnywhere(obj.getClass(), name);
            if (f != null) {
                return f.get(obj);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static boolean getBooleanFieldSafe(Object obj, String name, boolean def) {
        if (obj == null) return def;
        try {
            Field f = findFieldAnywhere(obj.getClass(), name);
            if (f != null) {
                return f.getBoolean(obj);
            }
        } catch (Throwable ignored) {}
        return def;
    }

    public static int getIntFieldSafe(Object obj, String name, int def) {
        if (obj == null) return def;
        try {
            Field f = findFieldAnywhere(obj.getClass(), name);
            if (f != null) {
                return f.getInt(obj);
            }
        } catch (Throwable ignored) {}
        return def;
    }

    public static void setSource(Object source) {
        currentSource = source;
        log("[BlurDrawer] setSource: " + source);
    }

    public static void clearSource(Object source) {
        if (currentSource == source || source == null) {
            log("[BlurDrawer] clearSource: " + source);
            currentSource = null;
            lastBitmap = null;
            bitmapShader = null;
        }
    }

    public static Bitmap ensureWallpaperBitmap() {
        if (currentSource != null) {
            Bitmap bmp = unwrapBitmap(currentSource);
            if (bmp != null && !bmp.isRecycled()) {
                return bmp;
            }
        }

        try {
            BaseFragment fragment = LaunchActivity.getLastFragment();
            if (fragment != null && fragment.getClass().getSimpleName().equals("ChatActivity")) {
                Object wallpaperSource = getFieldSafe(fragment, "navbarContentSourceWallpaper");
                if (wallpaperSource != null) {
                    Bitmap bmp = unwrapBitmap(wallpaperSource);
                    if (bmp != null && !bmp.isRecycled()) {
                        currentSource = wallpaperSource;
                        return bmp;
                    }
                }
            }
        } catch (Throwable t) {
            log("[BlurDrawer] Error retrieving ChatActivity wallpaper: " + t);
        }

        try {
            Drawable wallpaper = Theme.getCachedWallpaper();
            if (wallpaper != null) {
                if (wallpaper == lastWallpaperDrawable && fallbackBitmap != null && !fallbackBitmap.isRecycled()) {
                    return fallbackBitmap;
                }
                lastWallpaperDrawable = wallpaper;
                Bitmap bmp = extractBitmapFromAnyWallpaper(wallpaper);
                if (bmp != null) {
                    fallbackBitmap = bmp;
                    sourceMatrix.reset();
                    return fallbackBitmap;
                }
            }
        } catch (Throwable t) {
            log("[BlurDrawer] Error extracting Theme.getCachedWallpaper: " + t);
        }

        if (fallbackBitmap == null || fallbackBitmap.isRecycled()) {
            fallbackBitmap = createDefaultGlassPattern();
            sourceMatrix.reset();
        }
        return fallbackBitmap;
    }

    private static Bitmap unwrapBitmap(Object source) {
        if (source == null) return null;
        Object curr = source;
        while (curr != null) {
            try {
                Method getSource = findMethodAnywhere(curr.getClass(), "getSource");
                if (getSource != null) {
                    Object next = getSource.invoke(curr);
                    if (next == null || next == curr) break;
                    curr = next;
                } else {
                    break;
                }
            } catch (Throwable ignored) {
                break;
            }
        }
        if (curr != null) {
            try {
                Method getBitmap = findMethodAnywhere(curr.getClass(), "getBitmap");
                if (getBitmap != null) {
                    Bitmap bmp = (Bitmap) getBitmap.invoke(curr);
                    if (bmp != null && !bmp.isRecycled()) {
                        Method getMatrix = findMethodAnywhere(curr.getClass(), "getMatrix");
                        if (getMatrix != null) {
                            Matrix m = (Matrix) getMatrix.invoke(curr);
                            if (m != null) {
                                sourceMatrix.set(m);
                            }
                        }
                        return bmp;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Bitmap extractBitmapFromAnyWallpaper(Drawable drawable) {
        if (drawable == null) return null;

        // 1. BitmapDrawable
        if (drawable instanceof BitmapDrawable) {
            Bitmap bmp = ((BitmapDrawable) drawable).getBitmap();
            if (bmp != null && !bmp.isRecycled()) {
                return Utilities.stackBlurBitmapWithScaleFactor(bmp, 8.0f);
            }
        }

        // 2. MotionBackgroundDrawable
        if (drawable.getClass().getSimpleName().equals("MotionBackgroundDrawable")) {
            try {
                Method m = findMethodAnywhere(drawable.getClass(), "getBitmap");
                if (m != null) {
                    Bitmap bmp = (Bitmap) m.invoke(drawable);
                    if (bmp != null && !bmp.isRecycled()) {
                        return bmp;
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 3. ChatBackgroundDrawable (unravel inner drawable)
        if (drawable.getClass().getSimpleName().equals("ChatBackgroundDrawable")) {
            try {
                Method m = findMethodAnywhere(drawable.getClass(), "getDrawable", boolean.class);
                if (m != null) {
                    Drawable inner = (Drawable) m.invoke(drawable, false);
                    if (inner != null && inner != drawable) {
                        Bitmap bmp = extractBitmapFromAnyWallpaper(inner);
                        if (bmp != null) return bmp;
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 4. Any other drawable (ColorDrawable, GradientDrawable, ShapeDrawable, or custom)
        return renderAndBlurDrawable(drawable);
    }

    private static Bitmap renderAndBlurDrawable(Drawable drawable) {
        try {
            int w = 180;
            int h = 280;
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);

            if (drawable instanceof ColorDrawable) {
                int baseColor = ((ColorDrawable) drawable).getColor();
                Paint p = new Paint();
                p.setShader(new LinearGradient(0, 0, 0, h, new int[]{baseColor, Theme.multAlpha(baseColor, 0.85f)}, null, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w, h, p);
                Paint light = new Paint();
                light.setColor(0x20FFFFFF);
                canvas.drawCircle(w / 2.0f, h / 3.0f, w * 0.7f, light);
            } else {
                Rect bounds = new Rect(0, 0, w, h);
                Rect oldBounds = drawable.getBounds();
                drawable.setBounds(bounds);
                drawable.draw(canvas);
                drawable.setBounds(oldBounds);
            }

            Bitmap blurred = Utilities.stackBlurBitmapWithScaleFactor(bitmap, 4.0f);
            if (blurred != null) {
                return blurred;
            }
            return bitmap;
        } catch (Throwable t) {
            log("[BlurDrawer] renderAndBlurDrawable failed: " + t);
            return null;
        }
    }

    private static Bitmap createDefaultGlassPattern() {
        try {
            int w = 120;
            int h = 180;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.drawColor(0xFF263238);
            Paint p = new Paint();
            p.setColor(0x30FFFFFF);
            c.drawCircle(40, 50, 60, p);
            p.setColor(0x20000000);
            c.drawCircle(80, 130, 70, p);
            return Utilities.stackBlurBitmapWithScaleFactor(bmp, 4.0f);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean useGlass(MessageDrawable drawable, Paint customPaint) {
        if (!BlurConfig.enabled) return false;
        if (customPaint != null) return false;
        if (drawable != null) {
            if (getBooleanFieldSafe(drawable, "themePreview", false)) return false;
            if (getBooleanFieldSafe(drawable, "isCrossfadeBackground", false)) return false;
            if (getIntFieldSafe(drawable, "currentType", 0) == 2) return false;
        }
        return true;
    }

    public static int getGlassColor(MessageDrawable drawable) {
        if (drawable == null) return 0xFF2A52BE;
        try {
            boolean isOut = getBooleanFieldSafe(drawable, "isOut", false);
            boolean isSelected = getBooleanFieldSafe(drawable, "isSelected", false);
            if (isOut) {
                return drawable.getColor(isSelected ? Theme.key_chat_outBubbleSelected : Theme.key_chat_outBubble);
            } else {
                return drawable.getColor(isSelected ? Theme.key_chat_inBubbleSelected : Theme.key_chat_inBubble);
            }
        } catch (Throwable t) {
            return drawable.getColor(Theme.key_chat_inBubble);
        }
    }

    public static boolean handleDraw(MessageDrawable drawable, Canvas canvas, Paint customPaint) {
        try {
            if (!useGlass(drawable, customPaint)) {
                return false;
            }
            Path path = drawable.makePath();
            if (path == null) {
                return false;
            }
            int baseColor = getGlassColor(drawable);
            int alpha = drawable.getAlpha();

            boolean drawn = drawGlass(canvas, path, drawable, baseColor, alpha);
            if (drawn && getBooleanFieldSafe(drawable, "isSelected", false)) {
                int selColor = drawable.getColor(Theme.key_chat_outBubbleGradientSelectedOverlay);
                selectedPaint.setColor((selColor & 0x00FFFFFF) | (0x33 << 24));
                canvas.drawPath(path, selectedPaint);
            }
            return drawn;
        } catch (Throwable t) {
            log("[BlurDrawer] handleDraw error: " + t);
            return false;
        }
    }

    public static boolean handleDrawBackgroundInParent(Object cell) {
        if (!BlurConfig.enabled) return false;
        if (cell == null) return false;
        try {
            boolean canDraw = getBooleanFieldSafe(cell, "canDrawBackgroundInParent", false);
            if (!canDraw) return false;
            Object group = getFieldSafe(cell, "currentMessagesGroup");
            if (group != null) return false;
            Object msg = getFieldSafe(cell, "currentMessageObject");
            if (msg == null) return false;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean drawGlass(Canvas canvas, Path path, MessageDrawable drawable, int baseColor, int alpha) {
        try {
            if (alpha <= 0 || path == null || canvas == null) {
                return true;
            }
            Bitmap bmp = ensureWallpaperBitmap();
            if (bmp == null || bmp.isRecycled()) {
                return false;
            }

            if (bmp != lastBitmap || bitmapShader == null) {
                lastBitmap = bmp;
                bitmapShader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                glassPaint.setShader(bitmapShader);
            }

            // Read exact canvas transformation matrix in window coordinate space
            canvas.getMatrix(canvasMatrix);
            canvasMatrix.getValues(matrixValues);
            float canvasX = matrixValues[Matrix.MTRANS_X];
            float canvasY = matrixValues[Matrix.MTRANS_Y];

            Point display = AndroidUtilities.displaySize;
            float dispW = (float) display.x;
            float dispH = (float) display.y;

            // Scale wallpaper to display size if needed
            if (sourceMatrix.isIdentity()) {
                float scaleX = dispW / bmp.getWidth();
                float scaleY = dispH / bmp.getHeight();
                float scale = Math.max(scaleX, scaleY);
                matrix.setScale(scale, scale);
            } else {
                matrix.set(sourceMatrix);
            }

            // Offset shader by the exact canvas position relative to the root window (pixel-perfect sub-pixel alignment)
            matrix.postTranslate(-canvasX, -canvasY);
            bitmapShader.setLocalMatrix(matrix);

            // Clean frosted blur transparency (50%)
            int transparency = BlurConfig.getTransparency();
            int baseAlpha = Color.alpha(baseColor);
            if (baseAlpha == 0) baseAlpha = 255;
            int tintAlpha = (baseAlpha * (100 - transparency)) / 100;
            int tintColor = (baseColor & 0x00FFFFFF) | (tintAlpha << 24);

            if (tintColor != lastTintColor || tintColorFilter == null) {
                lastTintColor = tintColor;
                tintColorFilter = new PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_OVER);
            }

            glassPaint.setColorFilter(tintColorFilter);
            glassPaint.setAlpha(alpha);
            canvas.drawPath(path, glassPaint);

            // ExteraGram Liquid Glass Outline Style
            if (BlurConfig.isBorder()) {
                GlassOutlineStyle outlineStyle = ExteraConfig.getGlassOutlineStyle();
                float brightness = AndroidUtilities.computePerceivedBrightness(baseColor);
                boolean isBright = brightness > 0.72f;
                Rect bounds = drawable != null ? drawable.getBounds() : null;

                if (outlineStyle == GlassOutlineStyle.GLARE && bounds != null) {
                    // Top highlight line (bright glare reflection)
                    int topColor = isBright ? 0x40000000 : 0x85FFFFFF;
                    topStrokePaint.setColor(Theme.multAlpha(topColor, alpha / 255.0f));
                    topStrokePaint.setStrokeWidth(AndroidUtilities.dpf2(1.0f));

                    float clipHeight = Math.min(bounds.height() / 2.0f, AndroidUtilities.dp(22.0f));
                    canvas.save();
                    canvas.clipRect(bounds.left - 2, bounds.top - 2, bounds.right + 2, bounds.top + clipHeight);
                    canvas.drawPath(path, topStrokePaint);
                    canvas.restore();

                    // Bottom rim line (subtle edge shadow)
                    int bottomColor = isBright ? 0x20000000 : 0x40FFFFFF;
                    bottomStrokePaint.setColor(Theme.multAlpha(bottomColor, alpha / 255.0f));
                    bottomStrokePaint.setStrokeWidth(AndroidUtilities.dpf2(0.6666667f));

                    canvas.save();
                    canvas.clipRect(bounds.left - 2, bounds.bottom - clipHeight, bounds.right + 2, bounds.bottom + 2);
                    canvas.drawPath(path, bottomStrokePaint);
                    canvas.restore();
                } else if (outlineStyle == GlassOutlineStyle.SOLID) {
                    // Full outline line
                    int fullColor = isBright ? 0x35000000 : 0x60FFFFFF;
                    fullStrokePaint.setColor(Theme.multAlpha(fullColor, alpha / 255.0f));
                    fullStrokePaint.setStrokeWidth(AndroidUtilities.dpf2(1.0f));
                    canvas.drawPath(path, fullStrokePaint);
                }
            }

            return true;
        } catch (Throwable t) {
            log("[BlurDrawer] drawGlass error: " + t);
            return false;
        }
    }
}
