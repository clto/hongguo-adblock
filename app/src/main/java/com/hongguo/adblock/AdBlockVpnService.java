package com.hongguo.adblock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public class AdBlockVpnService extends VpnService {
    private static final String TAG = "AdBlockVpn";
    public static final String ACTION_START = "com.hongguo.adblock.START";
    public static final String ACTION_STOP = "com.hongguo.adblock.STOP";

    private static final String CHANNEL_ID = "adblock";
    private static final int NOTIFICATION_ID = 41;
    private static final int MAX_ROUTES = 400;
    private static final long REFRESH_MS = 10 * 60 * 1000L;

    private volatile boolean running;
    private volatile ParcelFileDescriptor tun;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private final AtomicLong sessionBlocked = new AtomicLong();
    private long lastPersisted;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopAll();
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createChannel();
        }
        startForeground(NOTIFICATION_ID, buildNotification("正在解析广告域名…"));
        Prefs.setVpnOn(this, true);
        if (!running) {
            startTunnel();
        }
        return START_STICKY;
    }

    private void startTunnel() {
        running = true;
        sessionBlocked.set(0);
        lastPersisted = 0;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hongguo:adblock");
            wakeLock.acquire();
        }
        worker = new Thread(this::runLoop, "adblock-tunnel");
        worker.setDaemon(true);
        worker.start();
    }

    private void runLoop() {
        while (running) {
            int routes = establishTunnel();
            if (routes <= 0) {
                updateNotification("等待网络，60 秒后重试");
                sleepQuietly(60_000);
                continue;
            }
            updateNotification("已拦截 " + sessionBlocked.get() + " 个连接 · " + routes + " 条路由");
            scheduleRefresh(tun);
            readPackets();
            if (!running) {
                break;
            }
            closeTun();
            sleepQuietly(3000);
        }
    }

    private void scheduleRefresh(final ParcelFileDescriptor fd) {
        Thread timer = new Thread(new Runnable() {
            @Override
            public void run() {
                sleepQuietly(REFRESH_MS);
                if (running && tun == fd) {
                    Log.i(TAG, "refreshing ad IP routes");
                    closeTun();
                }
            }
        }, "adblock-refresh");
        timer.setDaemon(true);
        timer.start();
    }

    private int establishTunnel() {
        closeTun();
        VpnService.Builder builder = new VpnService.Builder();
        builder.setSession("红果净化");
        builder.setMtu(1400);
        builder.setBlocking(true);
        try {
            builder.addAddress("10.111.0.2", 32);
            builder.addAddress("fd00:1111::2", 128);
        } catch (Exception e) {
            Log.w(TAG, "addAddress failed", e);
        }

        Set<String> added = new HashSet<>();
        int routes = 0;
        List<String> hosts = AdHosts.all(this);
        for (String host : hosts) {
            if (routes >= MAX_ROUTES) {
                break;
            }
            try {
                InetAddress[] addresses = InetAddress.getAllByName(host);
                for (InetAddress address : addresses) {
                    if (routes >= MAX_ROUTES) {
                        break;
                    }
                    String key = address.getHostAddress();
                    if (!added.add(key)) {
                        continue;
                    }
                    if (address instanceof Inet4Address) {
                        builder.addRoute(address, 32);
                        routes++;
                    } else if (address instanceof Inet6Address) {
                        builder.addRoute(address, 128);
                        routes++;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "resolve failed: " + host + " " + e.getMessage());
            }
        }
        if (routes == 0) {
            return 0;
        }
        try {
            ParcelFileDescriptor fd = builder.establish();
            if (fd == null) {
                Log.w(TAG, "establish returned null (VPN not prepared or another VPN active)");
                Prefs.setVpnOn(this, false);
                updateNotification("VPN 未授权或已有其他 VPN 占用");
                running = false;
                stopSelf();
                return 0;
            }
            tun = fd;
            Prefs.sp(this).edit().putInt(Prefs.KEY_ROUTES, routes).apply();
            Log.i(TAG, "tunnel established, routes=" + routes);
            return routes;
        } catch (Exception e) {
            Log.w(TAG, "establish failed", e);
            return 0;
        }
    }

    private void readPackets() {
        ParcelFileDescriptor fd = tun;
        if (fd == null) {
            return;
        }
        FileInputStream in = new FileInputStream(fd.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(fd.getFileDescriptor());
        byte[] buffer = new byte[32767];
        while (running) {
            int length;
            try {
                length = in.read(buffer);
            } catch (IOException e) {
                break;
            }
            if (length <= 0) {
                continue;
            }
            long total = sessionBlocked.incrementAndGet();
            byte[] rst = buildRst(buffer, length);
            if (rst != null) {
                try {
                    out.write(rst);
                } catch (IOException ignored) {
                }
            }
            if (total % 20 == 0) {
                persistBlocked();
                updateNotification("已拦截 " + total + " 个连接");
            }
        }
        persistBlocked();
    }

    private void persistBlocked() {
        long total = sessionBlocked.get();
        if (total <= lastPersisted) {
            return;
        }
        int base = Prefs.stat(this, Prefs.KEY_BLOCKED);
        Prefs.setStat(this, Prefs.KEY_BLOCKED, base + (int) (total - lastPersisted));
        lastPersisted = total;
    }

    private void stopAll() {
        running = false;
        closeTun();
        persistBlocked();
        Prefs.setVpnOn(this, false);
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {
        }
        stopSelf();
    }

    private void closeTun() {
        ParcelFileDescriptor fd = tun;
        tun = null;
        if (fd != null) {
            try {
                fd.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onRevoke() {
        Log.i(TAG, "VPN revoked");
        stopAll();
    }

    @Override
    public void onDestroy() {
        running = false;
        closeTun();
        persistBlocked();
        Prefs.setVpnOn(this, false);
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
        super.onDestroy();
    }

    // ---------------- notification ----------------

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "广告拦截",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("红果净化广告请求拦截状态");
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        PendingIntent content = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("红果净化 · 广告拦截")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(content);
        return b.build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        try {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        } catch (Exception ignored) {
        }
    }

    // ---------------- packet handling ----------------

    static byte[] buildRst(byte[] p, int length) {
        if (length < 20) {
            return null;
        }
        int version = (p[0] >> 4) & 0x0f;
        if (version == 4) {
            return buildRstV4(p, length);
        }
        if (version == 6) {
            return buildRstV6(p, length);
        }
        return null;
    }

    private static byte[] buildRstV4(byte[] p, int length) {
        int ihl = (p[0] & 0x0f) * 4;
        if (ihl < 20 || length < ihl + 20 || p[9] != 6) {
            return null;
        }
        int total = ((p[2] & 0xff) << 8) | (p[3] & 0xff);
        if (total < ihl + 20 || total > length) {
            total = length;
        }
        int tcp = ihl;
        int doff = ((p[tcp + 12] & 0xf0) >> 4) * 4;
        if (doff < 20 || total < ihl + doff) {
            return null;
        }
        int flags = p[tcp + 13] & 0xff;
        int seq = readInt(p, tcp + 4);
        int ack = readInt(p, tcp + 8);
        int payload = total - ihl - doff;
        int newSeq = (flags & 0x10) != 0 ? ack : 0;
        int newAck = seq + payload + ((flags & 0x02) != 0 ? 1 : 0) + ((flags & 0x01) != 0 ? 1 : 0);

        byte[] r = new byte[40];
        r[0] = 0x45;
        r[2] = 0;
        r[3] = 40;
        r[6] = 0x40;
        r[8] = 64;
        r[9] = 6;
        System.arraycopy(p, 16, r, 12, 4); // new src = old dst
        System.arraycopy(p, 12, r, 16, 4); // new dst = old src
        int ipSum = checksum(r, 0, 20, 0);
        r[10] = (byte) (ipSum >> 8);
        r[11] = (byte) ipSum;

        int t = 20;
        r[t] = p[tcp + 1];
        r[t + 1] = p[tcp];
        r[t + 2] = p[tcp + 3];
        r[t + 3] = p[tcp + 2];
        writeInt(r, t + 4, newSeq);
        writeInt(r, t + 8, newAck);
        r[t + 12] = 0x50;
        r[t + 13] = 0x14;

        int pseudo = sum16(r, 12, 4) + sum16(r, 16, 4) + 6 + 20;
        int tcpSum = checksum(r, t, 20, pseudo);
        r[t + 16] = (byte) (tcpSum >> 8);
        r[t + 17] = (byte) tcpSum;
        return r;
    }

    private static byte[] buildRstV6(byte[] p, int length) {
        if (length < 60 || p[6] != 6) {
            return null;
        }
        int payloadLen = ((p[4] & 0xff) << 8) | (p[5] & 0xff);
        int total = 40 + payloadLen;
        if (total > length) {
            total = length;
        }
        int tcp = 40;
        if (total < tcp + 20) {
            return null;
        }
        int doff = ((p[tcp + 12] & 0xf0) >> 4) * 4;
        if (doff < 20 || total < tcp + doff) {
            return null;
        }
        int flags = p[tcp + 13] & 0xff;
        int seq = readInt(p, tcp + 4);
        int ack = readInt(p, tcp + 8);
        int payload = total - tcp - doff;
        int newSeq = (flags & 0x10) != 0 ? ack : 0;
        int newAck = seq + payload + ((flags & 0x02) != 0 ? 1 : 0) + ((flags & 0x01) != 0 ? 1 : 0);

        byte[] r = new byte[60];
        r[0] = 0x60;
        r[4] = 0;
        r[5] = 20;
        r[6] = 6;
        r[7] = 64;
        System.arraycopy(p, 24, r, 8, 16);  // new src = old dst
        System.arraycopy(p, 8, r, 24, 16); // new dst = old src

        int t = 40;
        r[t] = p[tcp + 1];
        r[t + 1] = p[tcp];
        r[t + 2] = p[tcp + 3];
        r[t + 3] = p[tcp + 2];
        writeInt(r, t + 4, newSeq);
        writeInt(r, t + 8, newAck);
        r[t + 12] = 0x50;
        r[t + 13] = 0x14;

        int pseudo = sum16(r, 8, 16) + sum16(r, 24, 16) + 6 + 20;
        int tcpSum = checksum(r, t, 20, pseudo);
        r[t + 16] = (byte) (tcpSum >> 8);
        r[t + 17] = (byte) tcpSum;
        return r;
    }

    private static int checksum(byte[] b, int offset, int len, int initial) {
        long sum = initial & 0xffffffffL;
        int i = 0;
        while (i + 1 < len) {
            sum += ((b[offset + i] & 0xff) << 8) | (b[offset + i + 1] & 0xff);
            i += 2;
        }
        if (i < len) {
            sum += (b[offset + i] & 0xff) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xffff) + (sum >> 16);
        }
        return (int) (~sum) & 0xffff;
    }

    private static int sum16(byte[] b, int offset, int len) {
        int sum = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            sum += ((b[offset + i] & 0xff) << 8) | (b[offset + i + 1] & 0xff);
        }
        return sum;
    }

    private static int readInt(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16)
                | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    private static void writeInt(byte[] b, int o, int v) {
        b[o] = (byte) (v >> 24);
        b[o + 1] = (byte) (v >> 16);
        b[o + 2] = (byte) (v >> 8);
        b[o + 3] = (byte) v;
    }

}
