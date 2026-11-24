package com.hongguo.adblock;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class Prefs {
    static final String NAME = "hongguo_adblock";
    static final String KEY_VPN_ON = "vpn_on";
    static final String KEY_SKIP = "stat_skip";
    static final String KEY_BLOCKED = "stat_blocked";
    static final String KEY_HOSTS = "extra_hosts";
    static final String KEY_ROUTES = "vpn_routes";

    private Prefs() {
    }

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    private static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    static void bump(Context c, String key) {
        setStat(c, key, stat(c, key) + 1);
    }

    static void setStat(Context c, String key, int value) {
        SharedPreferences p = sp(c);
        String d = today();
        if (!d.equals(p.getString("stats_date", ""))) {
            p.edit().putString("stats_date", d).putInt(KEY_SKIP, 0).putInt(KEY_BLOCKED, 0).apply();
        }
        p.edit().putInt(key, Math.max(0, value)).apply();
    }

    static int stat(Context c, String key) {
        SharedPreferences p = sp(c);
        if (!today().equals(p.getString("stats_date", ""))) {
            return 0;
        }
        return p.getInt(key, 0);
    }

    static boolean vpnOn(Context c) {
        return sp(c).getBoolean(KEY_VPN_ON, false);
    }

    static void setVpnOn(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_VPN_ON, on).apply();
    }
}
