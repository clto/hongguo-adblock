package com.hongguo.adblock;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class AdHosts {
    // 从红果 7.4.3.32 (com.phoenix.read) dex 字符串中提取的广告域名。
    private static final String[] BASE = {
            "ad.zijieapi.com",
            "ads.zijieapi.com",
            "ads3-normal.zijieapi.com",
            "ads3-normal-hl.zijieapi.com",
            "ads3-normal-lf.zijieapi.com",
            "ads3-normal-lq.zijieapi.com",
            "ads5-normal.zijieapi.com",
            "ads5-normal-hl.zijieapi.com",
            "ads5-normal-lf.zijieapi.com",
            "ads5-normal-lq.zijieapi.com",
            "ad.oceanengine.com",
            "leads.oceanengine.com",
            "clue.oceanengine.com",
            "brand.oceanengine.com",
            "oceancus.com",
            "csjdeveloper.com",
            "scsjsd.com",
            "pangolin-sdk-toutiao.com",
            "api-access.pangolin-sdk-toutiao.com",
            "gromore.pangolin-sdk-toutiao.com",
            "pglstatp-toutiao.com",
            "sf3-fe-tos.pglstatp-toutiao.com",
            "pangolin-sdk-toutiao-b.com",
            "p3-sign.pangolin-sdk-toutiao-b.com",
            "p6-sign.pangolin-sdk-toutiao-b.com"
    };

    private AdHosts() {
    }

    static List<String> all(Context c) {
        Set<String> set = new LinkedHashSet<>();
        for (String h : BASE) {
            set.add(h);
        }
        SharedPreferences sp = Prefs.sp(c);
        String extra = sp.getString(Prefs.KEY_HOSTS, "");
        if (extra != null && !extra.isEmpty()) {
            for (String line : extra.split("\n")) {
                String h = normalize(line);
                if (h != null) {
                    set.add(h);
                }
            }
        }
        return new ArrayList<>(set);
    }

    static int customCount(Context c) {
        String extra = Prefs.sp(c).getString(Prefs.KEY_HOSTS, "");
        if (extra == null || extra.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (String line : extra.split("\n")) {
            if (normalize(line) != null) {
                n++;
            }
        }
        return n;
    }

    static boolean addCustom(Context c, String raw) {
        String h = normalize(raw);
        if (h == null || BASE_CONTAINS(h)) {
            return false;
        }
        SharedPreferences sp = Prefs.sp(c);
        Set<String> set = new LinkedHashSet<>();
        String extra = sp.getString(Prefs.KEY_HOSTS, "");
        if (extra != null && !extra.isEmpty()) {
            for (String line : extra.split("\n")) {
                String e = normalize(line);
                if (e != null) {
                    set.add(e);
                }
            }
        }
        set.add(h);
        StringBuilder sb = new StringBuilder();
        for (String e : set) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(e);
        }
        sp.edit().putString(Prefs.KEY_HOSTS, sb.toString()).apply();
        return true;
    }

    static void clearCustom(Context c) {
        Prefs.sp(c).edit().remove(Prefs.KEY_HOSTS).apply();
    }

    private static boolean BASE_CONTAINS(String h) {
        for (String b : BASE) {
            if (b.equals(h)) {
                return true;
            }
        }
        return false;
    }

    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String h = raw.trim().toLowerCase(Locale.US);
        if (h.isEmpty()) {
            return null;
        }
        int scheme = h.indexOf("://");
        if (scheme >= 0) {
            h = h.substring(scheme + 3);
        }
        int slash = h.indexOf('/');
        if (slash >= 0) {
            h = h.substring(0, slash);
        }
        int colon = h.indexOf(':');
        if (colon >= 0) {
            h = h.substring(0, colon);
        }
        if (h.startsWith("*.")) {
            h = h.substring(2);
        }
        if (!h.matches("[a-z0-9][a-z0-9.\\-]*\\.[a-z]{2,}")) {
            return null;
        }
        return h;
    }
}
