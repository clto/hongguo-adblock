package com.hongguo.adblock;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_VPN = 1001;
    private static final String TARGET_PACKAGE = "com.phoenix.read";

    private TextView accStatus;
    private Switch vpnSwitch;
    private TextView vpnState;
    private TextView tvStats;
    private TextView tvHosts;
    private EditText etHost;

    private boolean suppressSwitch;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        accStatus = findViewById(R.id.accStatus);
        vpnSwitch = findViewById(R.id.vpnSwitch);
        vpnState = findViewById(R.id.vpnState);
        tvStats = findViewById(R.id.tvStats);
        tvHosts = findViewById(R.id.tvHosts);
        etHost = findViewById(R.id.etHost);

        accStatus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    Toast.makeText(MainActivity.this,
                            "在列表中找到「红果广告自动跳过」并开启", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show();
                }
            }
        });

        vpnSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressSwitch) {
                return;
            }
            if (isChecked) {
                requestVpn();
            } else {
                stopVpn();
            }
        });

        Button open = findViewById(R.id.btnOpen);
        open.setOnClickListener(v -> {
            PackageManager pm = getPackageManager();
            Intent launch = pm.getLaunchIntentForPackage(TARGET_PACKAGE);
            if (launch == null) {
                Toast.makeText(this, "未找到红果免费短剧", Toast.LENGTH_SHORT).show();
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(launch);
            } catch (Exception e) {
                Toast.makeText(this, "无法打开红果", Toast.LENGTH_SHORT).show();
            }
        });

        Button addHost = findViewById(R.id.btnAddHost);
        addHost.setOnClickListener(v -> {
            String raw = etHost.getText().toString();
            if (TextUtils.isEmpty(raw.trim())) {
                return;
            }
            if (AdHosts.addCustom(this, raw)) {
                etHost.setText("");
                Toast.makeText(this, "已添加，重开 VPN 或等 10 分钟生效", Toast.LENGTH_SHORT).show();
                refresh();
            } else {
                Toast.makeText(this, "域名无效或已在列表中", Toast.LENGTH_SHORT).show();
            }
        });

        Button resetHosts = findViewById(R.id.btnResetHosts);
        resetHosts.setOnClickListener(v -> {
            AdHosts.clearCustom(this);
            Toast.makeText(this, "已清除自定义域名", Toast.LENGTH_SHORT).show();
            refresh();
        });
    }

    private void requestVpn() {
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            try {
                startActivityForResult(prepare, REQ_VPN);
            } catch (Exception e) {
                setSwitchSilently(false);
                Toast.makeText(this, "无法请求 VPN 权限", Toast.LENGTH_SHORT).show();
            }
        } else {
            startVpn();
        }
    }

    private void startVpn() {
        Intent intent = new Intent(this, AdBlockVpnService.class);
        intent.setAction(AdBlockVpnService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "正在建立广告黑洞（解析域名约需几秒）", Toast.LENGTH_SHORT).show();
    }

    private void stopVpn() {
        Intent intent = new Intent(this, AdBlockVpnService.class);
        intent.setAction(AdBlockVpnService.ACTION_STOP);
        try {
            startService(intent);
        } catch (Exception e) {
            Prefs.setVpnOn(this, false);
        }
        Toast.makeText(this, "已关闭广告拦截", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                startVpn();
            } else {
                setSwitchSilently(false);
                Toast.makeText(this, "未授予 VPN 权限", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void setSwitchSilently(boolean on) {
        suppressSwitch = true;
        vpnSwitch.setChecked(on);
        suppressSwitch = false;
    }

    private boolean accessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) {
            return false;
        }
        String full = getPackageName() + "/" + AdSkipService.class.getName();
        return enabled.contains(full)
                || enabled.contains(getPackageName() + "/.AdSkipService");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    private void refresh() {
        boolean acc = accessibilityEnabled();
        accStatus.setText(acc
                ? "无障碍服务：已开启（自动跳过生效中）"
                : "无障碍服务：未开启（点击去设置）");
        accStatus.setTextColor(acc ? 0xFF1B7F3A : 0xFFC62828);

        boolean vpn = Prefs.vpnOn(this);
        if (vpnSwitch.isChecked() != vpn) {
            setSwitchSilently(vpn);
        }
        int routes = Prefs.sp(this).getInt(Prefs.KEY_ROUTES, 0);
        if (!vpn) {
            vpnState.setText("未开启");
        } else if (routes == 0) {
            vpnState.setText("运行中 · 正在解析广告域名…");
        } else {
            vpnState.setText("运行中 · 已加入 " + routes + " 条广告 IP 路由");
        }

        int skip = Prefs.stat(this, Prefs.KEY_SKIP);
        int blocked = Prefs.stat(this, Prefs.KEY_BLOCKED);
        tvStats.setText("今日：跳过 " + skip + " 次 · 拦截 " + blocked + " 个连接");
        tvHosts.setText("当前拦截域名：" + AdHosts.all(this).size()
                + " 个（自定义 " + AdHosts.customCount(this) + " 个）");
    }
}
