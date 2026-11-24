package com.hongguo.adblock;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.regex.Pattern;

public class AdSkipService extends AccessibilityService {
    private static final String TAG = "AdSkip";
    private static final String TARGET_PACKAGE = "com.phoenix.read";

    private static final Pattern SKIP_WITH_COUNTDOWN =
            Pattern.compile("^(\\d{1,2}(s|秒)?)?跳过(广告)?(\\d{1,2}(s|秒)?)?$");
    private static final Pattern AD_SECONDS = Pattern.compile("^\\d{1,2}(s|秒)$");
    private static final String CONTINUE_HINT = "上滑继续观看短剧";

    private long lastActionAt;
    private long lastScanAt;
    private long lastSwipeAt;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (pkg != null && !TARGET_PACKAGE.contentEquals(pkg)) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastScanAt < 150) {
            return;
        }
        lastScanAt = now;

        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (Exception e) {
            return;
        }
        if (root == null) {
            return;
        }

        ScanResult result = scan(root);

        // 剧集流里插播的一屏广告：倒计时结束后底部出现“上滑继续观看短剧”，自动上滑翻过去
        if (result.continueHint) {
            if (now - lastSwipeAt >= 2500 && swipeUp()) {
                lastSwipeAt = now;
                lastActionAt = now;
                Prefs.bump(this, Prefs.KEY_SKIP);
                Log.i(TAG, "swiped ad page: continue hint");
            }
            return;
        }

        if (result.skipTarget != null && now - lastActionAt >= 700) {
            if (clickNode(result.skipTarget)) {
                lastActionAt = now;
                Prefs.bump(this, Prefs.KEY_SKIP);
                Log.i(TAG, "clicked ad button: " + result.skipLabel);
                return;
            }
        }

        // 广告页右上角无文字的关闭图标
        if (result.adSignal && result.cornerClose != null && now - lastActionAt >= 1200) {
            if (clickNode(result.cornerClose)) {
                lastActionAt = now;
                Prefs.bump(this, Prefs.KEY_SKIP);
                Log.i(TAG, "clicked top-right close icon");
            }
        }
    }

    private ScanResult scan(AccessibilityNodeInfo root) {
        ScanResult result = new ScanResult();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < 5000) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) {
                continue;
            }
            visited++;
            String raw = labelOf(node);
            String n = normalize(raw);
            if (n != null && !n.isEmpty()) {
                if (!result.adSignal && isAdLabel(n)) {
                    result.adSignal = true;
                }
                if (n.contains(CONTINUE_HINT)) {
                    result.continueHint = true;
                }
                if (isSkipLabel(n)) {
                    if (result.skipTarget == null) {
                        result.skipTarget = node;
                        result.skipLabel = raw;
                    }
                } else if (n.equals("关闭") && result.adSignal && isTopRight(node)) {
                    if (result.skipTarget == null) {
                        result.skipTarget = node;
                        result.skipLabel = raw;
                    }
                }
            }
            if (result.adSignal && result.cornerClose == null && isTopRightCloseIcon(node)) {
                result.cornerClose = node;
            }
            int count = node.getChildCount();
            for (int i = 0; i < count && visited + queue.size() < 5000; i++) {
                try {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        queue.add(child);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return result;
    }

    private static boolean isAdLabel(String n) {
        if (!n.contains("广告")) {
            return false;
        }
        // “免广告/无广告”这类是会员引导，不算广告页
        if (n.contains("免广告") || n.contains("无广告")) {
            return false;
        }
        if (n.contains("广告加载异常") || n.contains("跳过广告") || n.contains("关闭广告")) {
            return true;
        }
        return n.equals("广告") || n.startsWith("广告") || n.endsWith("广告");
    }

    static boolean isSkipLabel(String n) {
        if (n == null || n.isEmpty() || n.length() > 14) {
            return false;
        }
        if (n.contains("点击按钮") || n.contains("点击右上角")) {
            return false;
        }
        if (SKIP_WITH_COUNTDOWN.matcher(n).matches()) {
            return true;
        }
        if (n.contains("跳过广告") || n.contains("关闭广告") || n.contains("关闭此条广告")
                || n.contains("关闭此广告") || n.contains("点击关闭广告")) {
            return true;
        }
        if (n.equals("跳过片头") || n.equals("跳过片尾")) {
            return true;
        }
        if (n.equals("skip") || n.equals("skipad") || n.equals("skipads")
                || n.equals("close") || n.equals("closead")) {
            return true;
        }
        return false;
    }

    private boolean isTopRight(AccessibilityNodeInfo node) {
        Rect r = new Rect();
        try {
            node.getBoundsInScreen(r);
        } catch (Exception e) {
            return false;
        }
        Point size = screenSize();
        if (r.width() <= 0 || r.height() <= 0 || size.x <= 0) {
            return false;
        }
        int cx = r.centerX();
        int cy = r.centerY();
        return cx > size.x * 0.55f && cy < size.y * 0.30f;
    }

    private boolean isTopRightCloseIcon(AccessibilityNodeInfo node) {
        try {
            if (!node.isClickable() || !node.isEnabled() || node.getChildCount() > 0) {
                return false;
            }
            if (labelOf(node) != null) {
                return false;
            }
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            Point size = screenSize();
            if (size.x <= 0 || r.width() <= 0 || r.height() <= 0) {
                return false;
            }
            float density = getResources().getDisplayMetrics().density;
            int min = (int) (20 * density);
            int max = (int) (120 * density);
            if (r.width() < min || r.height() < min || r.width() > max || r.height() > max) {
                return false;
            }
            return r.centerX() > size.x * 0.72f && r.centerY() < size.y * 0.30f;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int depth = 0; depth < 6 && cur != null; depth++) {
            try {
                if (cur.isClickable() && cur.isEnabled()) {
                    if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true;
                    }
                    break;
                }
                cur = cur.getParent();
            } catch (Exception e) {
                return false;
            }
        }
        Rect r = new Rect();
        try {
            node.getBoundsInScreen(r);
        } catch (Exception e) {
            return false;
        }
        if (r.width() <= 0 || r.height() <= 0) {
            return false;
        }
        return tap(r.centerX(), r.centerY());
    }

    private boolean swipeUp() {
        Point size = screenSize();
        if (size.x <= 0 || size.y <= 0) {
            return false;
        }
        int x = size.x / 2;
        int startY = (int) (size.y * 0.74f);
        int endY = (int) (size.y * 0.20f);
        Path path = new Path();
        path.moveTo(x, startY);
        path.lineTo(x, endY);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 280))
                .build();
        try {
            return dispatchGesture(gesture, null, null);
        } catch (Exception e) {
            Log.w(TAG, "dispatchGesture failed", e);
            return false;
        }
    }

    private boolean tap(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 60))
                .build();
        try {
            return dispatchGesture(gesture, null, null);
        } catch (Exception e) {
            return false;
        }
    }

    private Point screenSize() {
        Point p = new Point();
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            Display display = wm.getDefaultDisplay();
            display.getRealSize(p);
        } catch (Exception e) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            p.x = dm.widthPixels;
            p.y = dm.heightPixels;
        }
        return p;
    }

    private static String labelOf(AccessibilityNodeInfo node) {
        CharSequence text = null;
        CharSequence desc = null;
        try {
            text = node.getText();
            desc = node.getContentDescription();
        } catch (Exception ignored) {
        }
        StringBuilder sb = new StringBuilder();
        if (text != null) {
            sb.append(text);
        }
        if (desc != null && desc.length() > 0) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(desc);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.replaceAll("\\s+", "")
                .replace("|", "")
                .replace("：", "")
                .replace(":", "")
                .replace("(", "")
                .replace(")", "")
                .replace("（", "")
                .replace("）", "")
                .toLowerCase(Locale.ROOT);
    }

    private static final class ScanResult {
        boolean adSignal;
        boolean continueHint;
        AccessibilityNodeInfo skipTarget;
        String skipLabel;
        AccessibilityNodeInfo cornerClose;
    }

    @Override
    public void onInterrupt() {
    }
}
