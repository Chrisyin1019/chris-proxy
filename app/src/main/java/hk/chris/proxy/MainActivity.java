package hk.chris.proxy;

import android.Manifest;
import androidx.appcompat.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Native UI following the Chris ERP Android client: a left drawer for
 * navigation (settings and a connection light at its foot), a dashboard home
 * ("is it alive?" then "what needs attention?"), three-line list rows that
 * open a detail sheet, and back that closes the drawer before leaving a page.
 * Routing is Surge-style: an ordered rule list where each rule (or shared rule
 * set) names its policy, then FINAL.
 */
public final class MainActivity extends AppCompatActivity {
    private static final int REQUEST_PERMISSIONS = 10;
    private static final int REQUEST_VPN = 11;
    private static final int REQUEST_RULE_FILE = 12;
    private static final int REQUEST_ADBLOCK = 13;
    private static final int REQUEST_QR = 14;
    private static final int REQUEST_EXPORT = 15;
    private static final int REQUEST_LIST_FILE = 16;
    private static final int REQUEST_CONFIG_FILE = 17;
    private static final int REQUEST_SAVE_SEALED = 18;
    private static final int REQUEST_SAVE_JSON = 19;
    private static final int MAX_RULE_ROWS = 300;
    private static final int MAX_SPLIT_RULES = 500;
    private static final String PREFS = "ui";
    private static final String KEY_THEME_MODE = "theme_mode";
    private static final String KEY_DEVICE_NAME = "device_name";

    private enum Screen { HOME, NODES, RULES, RULE_SETS, IMPORT, VOCABULARY, SETTINGS, SSIDS, LOGS, TRANSFER_EXPORT, TRANSFER_IMPORT, SUBSCRIPTIONS }
    private enum ExportStep { WAITING, REQUEST, APPROVED, DONE, FAILED }
    private enum ImportStep { CONNECTING, CHECK, OTP, FAILED }
    private enum CardStyle { PRIMARY, TONAL, WARNING, DANGER, PLAIN }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, String> latency = new HashMap<>();
    private Screen screen = Screen.HOME;
    private ProxyVpnService.State lastRenderedState;
    private static final int SHEET_PEEK_DP = 76;
    private LinearLayout attentionSheet;
    private com.google.android.material.bottomsheet.BottomSheetBehavior<LinearLayout> sheetBehavior;
    private TextView sheetSummary;
    private LinearLayout sheetList;
    private EgressProbe.Result egress;
    private boolean egressRunning;
    private String egressKey = "";
    private DrawerLayout drawer;
    private LinearLayout drawerList;
    private LinearLayout page;
    private ScrollView pageScroll;
    private TextView title;
    private ImageButton refreshButton;
    private TextView latencyLabel;
    private View connectionLight;
    private int connectionColor = Color.GRAY;
    private boolean latencyRunning;
    private boolean rulesExpanded;
    private Policy policy;
    private Routing routing;
    private VocabularyCodec.Snapshot vocabulary;
    private String vocabularyQuery = "";
    /** Text or URL waiting for the user to confirm an external import. */
    private ImportDraft importDraft;
    private androidx.activity.result.ActivityResultLauncher<com.journeyapps.barcodescanner.ScanOptions> scanner;
    // Live transfer state (exporter and importer side).
    private TransferServer transferServer;
    private TransferClient transferClient;
    private ExportStep exportStep;
    private ImportStep importStep;
    private String transferQr;
    private String transferPeer;
    private String transferCheck;
    private String transferOtp;
    private String transferMessage;
    private String transferAddress;
    // Sealed file / JSON waiting to be saved; cleared when the screen closes.
    private byte[] pendingSealed;
    private String pendingSealedCode;

    private final Runnable statusPoll = new Runnable() {
        @Override public void run() {
            if (screen == Screen.HOME && lastRenderedState != ProxyVpnService.state()) showHome();
            if (screen == Screen.HOME && !egressKey.equals(egressContext())) probeEgress(false);
            handler.postDelayed(this, 1000);
        }
    };

    /** Same thresholds as the ERP client: green under 460 ms, amber above, red on failure. */
    private final Runnable latencyTick = new Runnable() {
        @Override public void run() {
            measureLatency();
            handler.postDelayed(this, 15000);
        }
    };

    // ------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle state) {
        applyThemePreference();
        super.onCreate(state);
        try {
            policy = PolicyStore.load(this);
            routing = RoutingStore.load(this);
            try {
                vocabulary = VocabularyStore.load(this);
            } catch (Exception vocabularyError) {
                vocabulary = new VocabularyCodec.Snapshot(1L, 0L, "讀取失敗", new ArrayList<>());
                AppLogger.log(this, "WARN", "本機詞庫讀取失敗=" + vocabularyError.getClass().getSimpleName());
            }
            scanner = registerForActivityResult(new com.journeyapps.barcodescanner.ScanContract(),
                    result -> onScanned(result.getContents()));
            configureSystemBars();
            buildUi();
            getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
                @Override public void handleOnBackPressed() { goBack(); }
            });
            if (!handleExternalIntent(getIntent())) showHome();
        } catch (Throwable error) {
            showStartupError(error);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleExternalIntent(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(statusPoll);
        handler.post(latencyTick);
        refreshStaleRuleSets();
        refreshStaleSubscriptions();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(statusPoll);
        handler.removeCallbacks(latencyTick);
        super.onPause();
    }

    private void goBack() {
        if (drawer != null && drawer.isDrawerOpen(GravityCompat.START)) { drawer.closeDrawer(GravityCompat.START); return; }
        switch (screen) {
            case HOME: moveTaskToBack(true); return;
            case SSIDS:
            case LOGS: showSettings(); return;
            case RULE_SETS:
            case IMPORT: showRules(); return;
            case TRANSFER_EXPORT:
            case TRANSFER_IMPORT: showSettings(); return;
            case SUBSCRIPTIONS: showNodes(); return;
            default: showHome();
        }
    }

    // ----------------------------------------------------------------- shell

    private void showStartupError(Throwable error) {
        String type = error.getClass().getSimpleName();
        String detail = error.getMessage() == null ? "無詳細訊息" : error.getMessage();
        if (detail.length() > 240) detail = detail.substring(0, 240);
        AppLogger.log(this, "ERROR", "啟動失敗=" + type + ": " + detail);
        TextView recovery = text("Chris Proxy 無法完成啟動\n\n" + type + "\n" + detail
                + "\n\n錯誤已寫入運行記錄，不包含節點原文或密鑰。", 15, textColor());
        recovery.setPadding(dp(24), dp(48), dp(24), dp(24));
        recovery.setBackgroundColor(backgroundColor());
        setContentView(recovery);
    }

    private void configureSystemBars() {
        int background = backgroundColor();
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        if (Build.VERSION.SDK_INT >= 28) getWindow().setNavigationBarDividerColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        int flags = uiDark() ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(backgroundColor());

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        ImageButton menu = iconButton(R.drawable.ic_menu, "開啟選單");
        menu.setOnClickListener(v -> openDrawer());
        bar.addView(menu);
        title = text("Chris Proxy", 19, textColor());
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(8), 0, dp(8), 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        refreshButton = iconButton(R.drawable.ic_toolbar_refresh, "重新整理");
        refreshButton.setOnClickListener(v -> refreshCurrent());
        bar.addView(refreshButton);
        root.addView(bar);

        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(10), dp(18), dp(28));
        pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(true);
        pageScroll.addView(page);
        // CoordinatorLayout hosts the swipe-up "needs attention" panel on Home, like the ERP app.
        androidx.coordinatorlayout.widget.CoordinatorLayout content = new androidx.coordinatorlayout.widget.CoordinatorLayout(this);
        content.addView(pageScroll, new androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(-1, -1));
        attentionSheet = buildAttentionSheet();
        // Wrap content: expanded, the panel stops at its last row and the blurred page stays visible above.
        androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams sheetParams =
                new androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(-1, -2);
        sheetBehavior = new com.google.android.material.bottomsheet.BottomSheetBehavior<>();
        sheetBehavior.setPeekHeight(dp(SHEET_PEEK_DP));
        sheetBehavior.setHideable(false);
        sheetBehavior.setFitToContents(true);
        sheetParams.setBehavior(sheetBehavior);
        // Pulling the panel up blurs and dims the page behind it, in step with the drag.
        sheetBehavior.addBottomSheetCallback(new com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback() {
            @Override public void onStateChanged(View sheet, int newState) {
                if (newState == com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED) blurPage(0f);
                if (newState == com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED) blurPage(1f);
            }
            @Override public void onSlide(View sheet, float offset) { blurPage(Math.max(0f, offset)); }
        });
        content.addView(attentionSheet, sheetParams);
        attentionSheet.setVisibility(View.GONE);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        drawer = new DrawerLayout(this);
        drawer.addView(root, new DrawerLayout.LayoutParams(-1, -1));
        View panel = buildDrawerPanel();
        drawer.addView(panel, new DrawerLayout.LayoutParams(dp(304), -1, Gravity.START));
        // targetSdk 35+ is always edge-to-edge: keep the toolbar below the
        // status bar and the last row / drawer footer above the gesture bar.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(drawer, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                            | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            panel.setPadding(bars.left, bars.top, 0, bars.bottom);
            return androidx.core.view.WindowInsetsCompat.CONSUMED;
        });
        drawer.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override public void onDrawerStateChanged(int state) {
                if (state == DrawerLayout.STATE_DRAGGING && !drawer.isDrawerOpen(GravityCompat.START)) renderDrawer();
            }
        });
        setContentView(drawer);
    }

    private View buildDrawerPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(surfaceColor());
        float r = dp(20);
        shape.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
        panel.setBackground(shape);
        panel.setClickable(true);

        drawerList = new LinearLayout(this);
        drawerList.setOrientation(LinearLayout.VERTICAL);
        drawerList.setPadding(dp(12), dp(20), dp(12), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(drawerList);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        View line = new View(this);
        line.setBackgroundColor(lineColor());
        panel.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(12), dp(6), dp(16), dp(10));
        bottom.addView(drawerRow(R.drawable.ic_toolbar_settings, "設定", screen == Screen.SETTINGS, 0,
                () -> { closeDrawer(); showSettings(); }), new LinearLayout.LayoutParams(0, -2, 1));
        latencyLabel = text("未連線", 12, mutedColor());
        bottom.addView(latencyLabel);
        connectionLight = new View(this);
        LinearLayout.LayoutParams lightParams = new LinearLayout.LayoutParams(dp(8), dp(8));
        lightParams.setMargins(dp(8), 0, 0, 0);
        bottom.addView(connectionLight, lightParams);
        updateConnectionLight();
        panel.addView(bottom);
        return panel;
    }

    private void openDrawer() {
        renderDrawer();
        drawer.openDrawer(GravityCompat.START);
    }

    private void closeDrawer() {
        drawer.closeDrawer(GravityCompat.START);
    }

    private void renderDrawer() {
        drawerList.removeAllViews();
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), 0, dp(8), dp(16));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        header.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(12), 0, 0, 0);
        TextView name = text("Chris Proxy", 17, textColor());
        name.setTypeface(null, Typeface.BOLD);
        names.addView(name);
        names.addView(text(stateLabel(ProxyVpnService.state()) + " · " + routing.mode.label, 13, mutedColor()));
        header.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        drawerList.addView(header);

        drawerList.addView(drawerRow(R.drawable.ic_toolbar_home, "首頁", screen == Screen.HOME, 0,
                () -> { closeDrawer(); showHome(); }));
        drawerList.addView(drawerRow(R.drawable.ic_nodes, "節點", screen == Screen.NODES, 0,
                () -> { closeDrawer(); showNodes(); }));
        boolean inRules = screen == Screen.RULES || screen == Screen.RULE_SETS || screen == Screen.IMPORT;
        boolean expanded = rulesExpanded || inRules;
        drawerList.addView(drawerRow(R.drawable.ic_rules, "分流規則" + (expanded ? "  ▾" : "  ▸"), false, 0, () -> {
            rulesExpanded = !expanded;
            renderDrawer();
        }));
        if (expanded) {
            drawerList.addView(drawerRow(0, "規則（" + routing.rules.size() + "）", screen == Screen.RULES, 40,
                    () -> { closeDrawer(); showRules(); }));
            drawerList.addView(drawerRow(0, "規則集（" + RuleSetStore.list(this).size() + "）", screen == Screen.RULE_SETS, 40,
                    () -> { closeDrawer(); showRuleSets(); }));
            drawerList.addView(drawerRow(0, "匯入清單", screen == Screen.IMPORT, 40,
                    () -> { closeDrawer(); importMenu(); }));
        }
        drawerList.addView(drawerRow(R.drawable.ic_rules, "個人詞庫（" + vocabulary.entries.size() + "）",
                screen == Screen.VOCABULARY, 0, () -> { closeDrawer(); showVocabulary(); }));
        drawerList.addView(sectionLabel("診斷"));
        drawerList.addView(drawerRow(R.drawable.ic_log, "運行記錄", screen == Screen.LOGS, 0,
                () -> { closeDrawer(); showLogs(); }));
    }

    private View drawerRow(int icon, String label, boolean selected, int indentDp, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16 + indentDp), 0, dp(16), 0);
        row.setMinimumHeight(dp(indentDp > 0 ? 44 : 52));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(26));
        shape.setColor(selected ? raisedColor() : Color.TRANSPARENT);
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(dp(26));
        mask.setColor(Color.WHITE);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf((textColor() & 0x00FFFFFF) | 0x22000000), shape, mask));
        int tint = selected ? accentColor() : textColor();
        if (icon != 0) {
            ImageView image = new ImageView(this);
            image.setImageResource(icon);
            image.setImageTintList(ColorStateList.valueOf(tint));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(22), dp(22));
            lp.setMargins(0, 0, dp(14), 0);
            row.addView(image, lp);
        }
        TextView text = text(label, indentDp > 0 ? 14 : 15, indentDp > 0 && !selected ? mutedColor() : tint);
        if (selected) text.setTypeface(null, Typeface.BOLD);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(label);
        row.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(1), 0, dp(1));
        row.setLayoutParams(lp);
        return row;
    }

    private TextView sectionLabel(String value) {
        TextView view = text(value, 13, mutedColor());
        view.setPadding(dp(16), dp(18), 0, dp(6));
        return view;
    }

    private void enter(Screen next, String heading) {
        boolean transfer = next == Screen.TRANSFER_EXPORT || next == Screen.TRANSFER_IMPORT;
        if (transfer) {
            // Codes, OTPs and QR must not end up in screenshots or the recents preview.
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            closeTransfer();
        }
        beginPageTransition();
        screen = next;
        title.setText(heading);
        refreshButton.setVisibility(next == Screen.HOME || next == Screen.RULE_SETS || next == Screen.LOGS
                || next == Screen.SUBSCRIPTIONS
                ? View.VISIBLE : View.GONE);
        page.removeAllViews();
        pageScroll.scrollTo(0, 0);
        showAttentionSheet(false);
    }

    private LinearLayout buildAttentionSheet() {
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(surfaceColor());
        float r = dp(22);
        shape.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        shape.setStroke(dp(1), lineColor());
        sheet.setBackground(shape);
        sheet.setElevation(dp(8));
        sheet.setClickable(true);

        View handle = new View(this);
        GradientDrawable handleShape = new GradientDrawable();
        handleShape.setCornerRadius(dp(2));
        handleShape.setColor(lineColor());
        handle.setBackground(handleShape);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(36), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.setMargins(0, dp(10), 0, dp(10));
        sheet.addView(handle, handleParams);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), 0, dp(20), dp(12));
        TextView name = text("需要處理", 16, textColor());
        name.setTypeface(null, Typeface.BOLD);
        header.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        sheetSummary = text("", 13, mutedColor());
        header.addView(sheetSummary);
        header.setOnClickListener(v -> sheetBehavior.setState(
                sheetBehavior.getState() == com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                        ? com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
                        : com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED));
        sheet.addView(header);

        sheetList = new LinearLayout(this);
        sheetList.setOrientation(LinearLayout.VERTICAL);
        sheetList.setPadding(dp(20), 0, dp(20), dp(28));
        androidx.core.widget.NestedScrollView scroll = new androidx.core.widget.NestedScrollView(this);
        scroll.addView(sheetList);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        return sheet;
    }

    private void blurPage(float amount) {
        if (pageScroll == null) return;
        pageScroll.setAlpha(1f - 0.35f * amount);
        if (Build.VERSION.SDK_INT >= 31) {
            float radius = dp(24) * amount;
            pageScroll.setRenderEffect(radius < 0.5f ? null
                    : android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP));
        }
    }

    private void showAttentionSheet(boolean visible) {
        if (attentionSheet == null) return;
        attentionSheet.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) blurPage(0f);
        if (visible) sheetBehavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED);
        // Keep the last home row reachable above the collapsed panel.
        page.setPadding(dp(18), dp(10), dp(18), dp(28) + (visible ? dp(SHEET_PEEK_DP) : 0));
    }

    private void refreshCurrent() {
        if (screen == Screen.RULE_SETS) refreshAllRuleSets();
        else if (screen == Screen.SUBSCRIPTIONS) refreshSubscriptions(SubscriptionStore.list(this), true);
        else if (screen == Screen.LOGS) showLogs();
        else { measureLatency(true); probeEgress(true); }
    }

    /** 180 ms fade with an 8 dp rise, skipped when system animations are off. */
    private void beginPageTransition() {
        if (page == null || !page.isLaidOut()) return;
        float scale = Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        if (scale <= 0) return;
        page.animate().cancel();
        page.setAlpha(0f);
        page.setTranslationY(dp(8));
        page.animate().alpha(1f).translationY(0f).setDuration(180)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    // ------------------------------------------------------------- dashboard

    private void showHome() {
        enter(Screen.HOME, "Chris Proxy");
        ProxyVpnService.State state = ProxyVpnService.state();
        lastRenderedState = state;
        List<ProxyProfile> profiles = ProxyProfileStore.load(this);
        ProxyProfile selected = ProxyProfileStore.selected(this);
        List<RuleSet> sets = RuleSetStore.list(this);

        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String greeting = hour >= 5 && hour < 11 ? "早安" : hour >= 11 && hour < 18 ? "午安" : "晚安";
        String ssid = SsidResolver.currentSsid(this);
        boolean wifiException = !ssid.startsWith("<") && policy.directSsids.contains(ssid.trim());
        boolean live = state == ProxyVpnService.State.RUNNING || state == ProxyVpnService.State.STARTING;

        // Open layout, no card: each fact appears once. Wi-Fi only shows up in "路線".
        TextView hello = text(greeting + "，" + deviceName(), 15, mutedColor());
        hello.setPadding(dp(2), dp(4), 0, dp(20));
        page.addView(hello);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        setDot(dot, state == ProxyVpnService.State.RUNNING ? successColor()
                : state == ProxyVpnService.State.FAILED ? dangerColor()
                : state == ProxyVpnService.State.STARTING ? amberColor() : Color.GRAY);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(12), dp(12));
        dotParams.setMargins(dp(2), 0, dp(12), 0);
        statusRow.addView(dot, dotParams);
        TextView big = text(stateLabel(state), 30, textColor());
        big.setTypeface(null, Typeface.BOLD);
        statusRow.addView(big, new LinearLayout.LayoutParams(0, -2, 1));
        TextView modeLabel = text(routing.mode.label + " ▾", 14, accentColor());
        modeLabel.setPadding(dp(12), dp(10), dp(4), dp(10));
        modeLabel.setOnClickListener(v -> chooseMode());
        modeLabel.setContentDescription("出站模式：" + routing.mode.label + "，點一下切換");
        statusRow.addView(modeLabel);
        page.addView(statusRow);

        View gap = new View(this);
        page.addView(gap, new LinearLayout.LayoutParams(-1, dp(24)));
        infoRow("節點", selected == null ? "尚未新增" : selected.label, this::showNodes);
        if (live) infoRow("延遲", latencyLabel == null ? "—" : latencyLabel.getText().toString(), () -> measureLatency(true));
        infoRow("出口", exitValue(), () -> probeEgress(true));
        infoRow("路線", routeValue(state, ssid, wifiException), null);

        Button toggle;
        if (profiles.isEmpty()) {
            toggle = pillButton("新增節點", true);
            toggle.setOnClickListener(v -> showNodes());
        } else if (live) {
            toggle = pillButton(state == ProxyVpnService.State.STARTING ? "連線中…" : "中斷連線", false);
            toggle.setOnClickListener(v -> stopProxy());
        } else {
            toggle = pillButton("開始連線", true);
            toggle.setOnClickListener(v -> prepareVpn());
        }
        toggle.setTextSize(16);
        toggle.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(-1, dp(56));
        toggleParams.setMargins(0, dp(32), 0, 0);
        page.addView(toggle, toggleParams);
        if (egress == null || System.currentTimeMillis() - egress.time > 60_000) probeEgress(false);
        if (state == ProxyVpnService.State.RUNNING && latencyLabel != null
                && "未連線".contentEquals(latencyLabel.getText())) measureLatency(true);

        // "What needs a person" lives in the swipe-up panel; routing lives in the drawer.
        int failed = 0;
        int stale = 0;
        long now = System.currentTimeMillis();
        for (RuleSet set : sets) {
            if (!set.error.isEmpty()) failed++;
            else if (now - set.updatedAt > 7L * 24 * 60 * 60 * 1000 && set.updatable()) stale++;
        }
        int subFailed = 0;
        for (SubscriptionStore.Subscription sub : SubscriptionStore.list(this)) if (!sub.error.isEmpty()) subFailed++;
        int lastFailed = state == ProxyVpnService.State.FAILED ? 1 : 0;
        int orphans = RoutingStore.orphaned(routing, profiles).size();
        int items = (lastFailed > 0 ? 1 : 0) + (orphans > 0 ? 1 : 0) + (failed > 0 ? 1 : 0) + (stale > 0 ? 1 : 0) + (subFailed > 0 ? 1 : 0);
        sheetSummary.setText(items == 0 ? "一切正常" : items + " 項");
        sheetSummary.setTextColor(items > 0 ? amberColor() : mutedColor());
        sheetSummary.setTypeface(null, items > 0 ? Typeface.BOLD : Typeface.NORMAL);
        sheetList.removeAllViews();
        boolean wasExpanded = sheetBehavior.getState() == com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED;
        showAttentionSheet(true);
        if (wasExpanded) sheetBehavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        LinearLayout attention = sheetList;
        attentionRow(attention, R.drawable.ic_log, "上次連線失敗", lastFailed, ProxyVpnService.lastError(), this::showLogs);
        attentionRow(attention, R.drawable.ic_rules, "規則指向已刪除的節點", orphans, null, this::showRules);
        attentionRow(attention, R.drawable.ic_rules, "規則集更新失敗", failed, null, this::showRuleSets);
        attentionRow(attention, R.drawable.ic_rules, "規則集超過 7 天未更新", stale, null, this::showRuleSets);
        attentionRow(attention, R.drawable.ic_nodes, "訂閱更新失敗", subFailed, null, this::showSubscriptions);
    }

    /** Connection state + Wi-Fi name: when either changes the exit may change. */
    private String egressContext() {
        return ProxyVpnService.state() + "|" + SsidResolver.currentSsid(this);
    }

    private String exitValue() {
        if (egress == null) return egressRunning ? "檢測中…" : "—";
        if (!egress.error.isEmpty()) return "無法檢測 · 點一下重試";
        return egress.ip + (egress.place.isEmpty() ? "" : "\n" + egress.place);
    }

    /** How traffic leaves right now; the one place Wi-Fi exceptions are mentioned. */
    private String routeValue(ProxyVpnService.State state, String ssid, boolean wifiException) {
        if (state != ProxyVpnService.State.RUNNING) return "未連線，直接連網";
        String via = egress == null || !egress.error.isEmpty() || egress.route.isEmpty() ? null : egress.route;
        if (wifiException) return "直連 · Wi-Fi「" + ssid + "」例外";
        if (via != null) return via + " · " + routing.mode.label;
        return routing.mode.label;
    }

    /** Label on the left, value on the right; a hairline separates rows. */
    private void infoRow(String label, String value, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(14), dp(2), dp(14));
        TextView name = text(label, 14, mutedColor());
        row.addView(name, new LinearLayout.LayoutParams(dp(64), -2));
        TextView v = text(value, 16, textColor());
        v.setLineSpacing(dp(2), 1f);
        row.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) {
            android.util.TypedValue bg = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, bg, true);
            row.setBackgroundResource(bg.resourceId);
            row.setOnClickListener(x -> action.run());
        }
        row.setContentDescription(label + "：" + value);
        page.addView(row);
        View line = new View(this);
        line.setBackgroundColor(lineColor());
        page.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2)));
    }

    private void probeEgress(boolean force) {
        if (egressRunning) return;
        if (!force && ProxyVpnService.state() == ProxyVpnService.State.STARTING) return;
        egressRunning = true;
        egressKey = egressContext();
        new Thread(() -> {
            EgressProbe.Result result = EgressProbe.run(this);
            runOnUiThread(() -> {
                egressRunning = false;
                egress = result;
                if (screen == Screen.HOME) showHome();
            });
        }, "egress-probe").start();
    }

    private static String stateLabel(ProxyVpnService.State state) {
        switch (state) {
            case RUNNING: return "已連線";
            case STARTING: return "連線中…";
            case FAILED: return "連線失敗";
            default: return "未連線";
        }
    }


    /** Open row like the home rows: icon, label (+ detail), count; hairline below. */
    private void attentionRow(LinearLayout parent, int icon, String label, int count, String detail, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(14), dp(8), dp(14));
        android.util.TypedValue bg = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, bg, true);
        row.setBackgroundResource(bg.resourceId);
        ImageView image = new ImageView(this);
        image.setImageResource(icon);
        image.setImageTintList(ColorStateList.valueOf(mutedColor()));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(20), dp(20));
        iconParams.setMargins(0, 0, dp(16), 0);
        row.addView(image, iconParams);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(label, 15, textColor()));
        if (count > 0 && detail != null && !detail.isEmpty()) texts.addView(text(detail, 12, mutedColor()));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = text(String.valueOf(count), 18, count > 0 ? amberColor() : mutedColor());
        value.setTypeface(null, Typeface.BOLD);
        row.addView(value);
        row.setClickable(true);
        row.setOnClickListener(v -> action.run());
        row.setContentDescription(label + " " + count);
        parent.addView(row);
        View line = new View(this);
        line.setBackgroundColor(lineColor());
        parent.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2)));
    }

    private static String modeDescription(Routing.Mode mode) {
        switch (mode) {
            case GLOBAL: return "除區域網路外，所有流量都走目前節點";
            case DIRECT: return "VPN 保持開啟，但所有流量直接連線（排查問題用）";
            default: return "依規則由上往下比對，符合的第一條決定走向";
        }
    }

    private void chooseMode() {
        Routing.Mode[] modes = Routing.Mode.values();
        String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) labels[i] = modes[i].label + "\n" + modeDescription(modes[i]);
        dialog().setTitle("出站模式")
                .setSingleChoiceItems(labels, routing.mode.ordinal(), (d, which) -> {
                    d.dismiss();
                    saveRouting(routing.withMode(modes[which]));
                    AppLogger.log(this, "INFO", "出站模式=" + modes[which].name());
                    if (screen == Screen.HOME) showHome(); else showRules();
                })
                .setNegativeButton("取消", null).show();
    }

    private long lastRealTest;

    /** Home light: real URL test through the nodes, at most every two minutes unless forced. */
    private void measureLatency() {
        measureLatency(false);
    }

    private void measureLatency(boolean force) {
        if (latencyLabel == null) return;
        ProxyProfile selected = ProxyVpnService.state() == ProxyVpnService.State.RUNNING
                ? ProxyProfileStore.selected(this) : null;
        if (selected == null) {
            latencyLabel.setText("未連線");
            connectionColor = Color.GRAY;
            updateConnectionLight();
            return;
        }
        if (!force && System.currentTimeMillis() - lastRealTest < 120_000) return;
        if (RealLatencyTester.isRunning()) return;
        lastRealTest = System.currentTimeMillis();
        runRealLatencyTest(ProxyProfileStore.load(this));
    }

    private void runRealLatencyTest(List<ProxyProfile> profiles) {
        if (profiles.isEmpty() || RealLatencyTester.isRunning()) return;
        for (ProxyProfile p : profiles) latency.put(p.id(), "測試中");
        if (screen == Screen.NODES) showNodes();
        RealLatencyTester.testAll(this, profiles, (delays, reasons, error) -> runOnUiThread(() -> {
            for (ProxyProfile p : profiles) {
                Integer d = delays.get(p.id());
                String why = reasons.get(p.id());
                latency.put(p.id(), error != null ? "無法測試" : d != null && d > 0 ? d + " ms"
                        : why != null ? why : "逾時／失敗");
            }
            if (error != null) toast("延遲測試失敗：" + error);
            ProxyProfile selected = ProxyProfileStore.selected(this);
            if (latencyLabel != null && selected != null && ProxyVpnService.state() == ProxyVpnService.State.RUNNING) {
                Integer d = delays.get(selected.id());
                latencyLabel.setText(d == null || d < 0 ? "失敗" : d + " ms");
                connectionColor = d == null || d < 0 ? dangerColor() : d >= 460 ? amberColor() : successColor();
                updateConnectionLight();
            }
            if (screen == Screen.NODES) showNodes();
            else if (screen == Screen.HOME) showHome();
        }));
    }

    private void updateConnectionLight() {
        if (connectionLight == null) return;
        setDot(connectionLight, connectionColor);
        connectionLight.setContentDescription(connectionColor == Color.GRAY ? "未連線" : latencyLabel.getText());
    }

    // ---------------------------------------------------------------- nodes

    private void showNodes() {
        enter(Screen.NODES, "節點");
        List<ProxyProfile> profiles = ProxyProfileStore.load(this);
        int selectedIndex = ProxyProfileStore.selectedIndex(this);
        page.addView(card(0, "新增節點", "貼上 vless://、ss://、hy2:// 或 WireGuard 設定", CardStyle.PRIMARY, this::addNodeDialog));
        page.addView(card(0, "掃描 QR 新增", "相機掃描節點 QR", CardStyle.TONAL, this::scanProfileQr));
        List<SubscriptionStore.Subscription> subs = SubscriptionStore.list(this);
        page.addView(card(0, "訂閱", subs.isEmpty() ? "從訂閱網址匯入節點，每天自動更新"
                : subs.size() + " 個訂閱", CardStyle.PLAIN, this::showSubscriptions));
        if (profiles.isEmpty()) {
            muted("節點以 Android Keystore 加密保存。");
            return;
        }
        page.addView(card(0, "測試全部延遲", "經由每個節點實際握手並連線測量，約 10–20 秒", CardStyle.PLAIN, this::testAllLatency));
        page.addView(sectionLabel("已保存（" + profiles.size() + "）"));
        for (int i = 0; i < profiles.size(); i++) {
            final int index = i;
            ProxyProfile profile = profiles.get(i);
            boolean inUse = i == selectedIndex;
            String result = latency.get(profile.id());
            int referenced = 0;
            for (RouteRule rule : routing.rules) if (profile.id().equals(rule.nodeId())) referenced++;
            String source = SubscriptionStore.sourceOf(subs, profile.id());
            listRow((inUse ? "✓ " : "") + profile.label,
                    profile.type.name() + (inUse ? " · 使用中" : "") + (source == null ? "" : " · 訂閱：" + source),
                    (result == null ? "尚未測速" : result) + (referenced > 0 ? " · " + referenced + " 條規則指定" : ""),
                    inUse ? accentColor() : textColor(),
                    () -> nodeSheet(index, profile, inUse));
        }
    }

    private void nodeSheet(int index, ProxyProfile profile, boolean inUse) {
        BottomSheetDialog sheet = sheet();
        LinearLayout body = sheetBody(sheet, profile.label, profile.type.name() + " · ID " + profile.id());
        if (!inUse) {
            body.addView(card(0, "設為使用中", "沒有指定節點的代理流量都會走這裡", CardStyle.PRIMARY, () -> {
                sheet.dismiss();
                ProxyProfileStore.setSelectedIndex(this, index);
                AppLogger.log(this, "INFO", "切換目前節點=" + profile.id());
                applyChange();
                showNodes();
            }));
        }
        body.addView(card(0, "新增指定此節點的規則", "例如某個網域或 App 一律走這個節點", CardStyle.TONAL, () -> {
            sheet.dismiss();
            editRule(-1, RouteRule.nodeTarget(profile.id()));
        }));
        body.addView(card(0, "刪除節點", "無法復原", CardStyle.DANGER, () -> {
            sheet.dismiss();
            confirmRemoveNode(index, profile);
        }));
        sheet.show();
    }

    private void addNodeDialog() {
        EditText input = field("貼上一個或多個節點", false);
        input.setMinLines(4);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        AlertDialog d = dialog().setTitle("新增節點").setView(padded(input))
                .setPositiveButton("新增", null).setNegativeButton("取消", null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (addProfiles(input.getText().toString())) d.dismiss();
        }));
        d.show();
    }

    private boolean addProfiles(String raw) {
        try {
            List<ProxyProfile> existing = ProxyProfileStore.load(this);
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (ProxyProfile p : existing) ids.add(p.id());
            int added = 0;
            int duplicate = 0;
            for (ProxyProfile profile : ProxyProfileParser.parseMany(raw)) {
                if (!ids.add(profile.id())) { duplicate++; continue; }
                existing.add(profile);
                added++;
            }
            ProxyProfileStore.replace(this, existing);
            AppLogger.log(this, "INFO", "新增節點數=" + added);
            toast(added == 0 ? "這些節點都已存在" : "已新增 " + added + " 個節點" + (duplicate > 0 ? "，略過重複 " + duplicate + " 個" : ""));
            applyChange();
            showNodes();
            return true;
        } catch (Exception e) {
            AppLogger.log(this, "WARN", "節點新增失敗，原因類型=" + e.getClass().getSimpleName());
            toast(e.getMessage());
            return false;
        }
    }

    private void confirmRemoveNode(int index, ProxyProfile profile) {
        int referenced = 0;
        for (RouteRule rule : routing.rules) if (profile.id().equals(rule.nodeId())) referenced++;
        String message = "「" + profile.label + "」會從這支手機移除，無法復原。"
                + (referenced > 0 ? "\n\n有 " + referenced + " 條規則指定此節點，刪除後會改走目前使用的節點。" : "");
        dialog().setTitle("刪除節點？").setMessage(message)
                .setPositiveButton("刪除", (d, w) -> {
                    ProxyProfileStore.remove(this, index);
                    AppLogger.log(this, "INFO", "刪除節點=" + profile.id());
                    applyChange();
                    showNodes();
                })
                .setNegativeButton("取消", null).show();
    }

    private void testAllLatency() {
        if (RealLatencyTester.isRunning()) { toast("延遲測試進行中"); return; }
        runRealLatencyTest(ProxyProfileStore.load(this));
    }

    private void scanProfileQr() {
        launchScanner("掃描節點 QR");
    }

    private void launchScanner(String prompt) {
        com.journeyapps.barcodescanner.ScanOptions options = new com.journeyapps.barcodescanner.ScanOptions()
                .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                .setPrompt(prompt)
                .setBeepEnabled(false)
                .setOrientationLocked(true);
        scanner.launch(options);
    }

    /** One scanner for everything: transfer QR, then node links. */
    private void onScanned(String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (TransferClient.isTransferQr(value)) startLiveImport(value);
        else addProfiles(value);
    }

    // ---------------------------------------------------------- subscriptions

    private void showSubscriptions() {
        enter(Screen.SUBSCRIPTIONS, "訂閱");
        List<SubscriptionStore.Subscription> subs = SubscriptionStore.list(this);
        muted("訂閱網址會回傳節點清單（vless、ss、hy2）。更新時加入新節點、移除訂閱已刪除的節點，不影響你手動新增的節點。每天自動更新，右上角可立即更新。");
        page.addView(card(0, "新增訂閱", "貼上訂閱網址", CardStyle.PRIMARY, this::addSubscriptionDialog));
        if (subs.isEmpty()) return;
        page.addView(sectionLabel("已訂閱（" + subs.size() + "）"));
        for (SubscriptionStore.Subscription sub : subs) {
            String third = !sub.error.isEmpty() ? "更新失敗：" + sub.error
                    : sub.updatedAt == 0 ? "尚未更新" : "更新於 " + ago(sub.updatedAt);
            listRow(sub.name, sub.nodeIds.size() + " 個節點", third,
                    sub.error.isEmpty() ? textColor() : dangerColor(), () -> subscriptionSheet(sub));
        }
    }

    private void addSubscriptionDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(8), dp(22), 0);
        EditText name = field("名稱（可留空）", true);
        EditText url = field("https://…", true);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0) {
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            if (text != null && text.toString().trim().matches("(?i)https?://\\S+")) url.setText(text.toString().trim());
        }
        form.addView(name);
        form.addView(url);
        AlertDialog d = dialog().setTitle("新增訂閱")
                .setMessage("訂閱網址通常含有個人權杖，會加密保存在這支手機。")
                .setView(form).setPositiveButton("匯入", null).setNegativeButton("取消", null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = url.getText().toString().trim();
            if (!value.matches("(?i)https?://\\S+")) { url.setError("請輸入 http:// 或 https:// 網址"); return; }
            d.dismiss();
            SubscriptionStore.Subscription sub = SubscriptionStore.add(this, name.getText().toString(), value);
            refreshSubscriptions(java.util.Collections.singletonList(sub), true);
        }));
        d.show();
    }

    private void subscriptionSheet(SubscriptionStore.Subscription sub) {
        BottomSheetDialog sheet = sheet();
        LinearLayout body = sheetBody(sheet, sub.name, sub.nodeIds.size() + " 個節點"
                + (sub.updatedAt == 0 ? "" : " · 更新於 " + ago(sub.updatedAt)));
        body.addView(card(0, "立即更新", null, CardStyle.PRIMARY, () -> {
            sheet.dismiss();
            refreshSubscriptions(java.util.Collections.singletonList(sub), true);
        }));
        body.addView(card(0, "重新命名", null, CardStyle.PLAIN, () -> {
            sheet.dismiss();
            EditText input = field("名稱", true);
            input.setText(sub.name);
            dialog().setTitle("重新命名").setView(padded(input))
                    .setPositiveButton("儲存", (d, w) -> {
                        SubscriptionStore.rename(this, sub.id, input.getText().toString());
                        showSubscriptions();
                    })
                    .setNegativeButton("取消", null).show();
        }));
        body.addView(card(0, "刪除訂閱", null, CardStyle.DANGER, () -> {
            sheet.dismiss();
            dialog().setTitle("刪除「" + sub.name + "」？")
                    .setMessage("要一併移除這個訂閱帶來的 " + sub.nodeIds.size() + " 個節點嗎？")
                    .setPositiveButton("刪除並移除節點", (d, w) -> {
                        SubscriptionStore.delete(this, sub.id, true);
                        applyChange();
                        showSubscriptions();
                    })
                    .setNeutralButton("只刪除訂閱", (d, w) -> {
                        SubscriptionStore.delete(this, sub.id, false);
                        showSubscriptions();
                    })
                    .setNegativeButton("取消", null).show();
        }));
        sheet.show();
    }

    private void refreshStaleSubscriptions() {
        ArrayList<SubscriptionStore.Subscription> stale = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (SubscriptionStore.Subscription sub : SubscriptionStore.list(this)) if (sub.stale(now)) stale.add(sub);
        if (!stale.isEmpty()) refreshSubscriptions(stale, false);
    }

    private void refreshSubscriptions(List<SubscriptionStore.Subscription> subs, boolean announce) {
        if (subs.isEmpty()) { if (announce) toast("沒有訂閱"); return; }
        if (announce) toast("正在更新訂閱…");
        new Thread(() -> {
            StringBuilder summary = new StringBuilder();
            for (SubscriptionStore.Subscription sub : subs) {
                SubscriptionStore.Outcome o = SubscriptionStore.refresh(this, sub);
                if (summary.length() > 0) summary.append('\n');
                summary.append(sub.name).append("：").append(o.error.isEmpty()
                        ? o.total + " 個節點（新增 " + o.added + "、移除 " + o.removed + ")"
                                + (o.unsupported > 0 ? "，略過 " + o.unsupported + " 個不支援" : "")
                        : "失敗 " + o.error);
            }
            runOnUiThread(() -> {
                applyChange();
                if (announce) toast(summary.toString());
                if (screen == Screen.SUBSCRIPTIONS) showSubscriptions();
                else if (screen == Screen.NODES) showNodes();
                else if (screen == Screen.HOME) showHome();
            });
        }, "subscription-refresh").start();
    }

    // ---------------------------------------------------------------- rules

    private void showRules() {
        enter(Screen.RULES, "分流規則");
        List<ProxyProfile> profiles = ProxyProfileStore.load(this);
        Map<String, RuleSet> sets = ruleSetMap();
        if (routing.mode != Routing.Mode.RULE) {
            page.addView(card(0, "目前是「" + routing.mode.label + "」", "下列規則只在規則模式生效，點一下切換",
                    CardStyle.WARNING, this::chooseMode));
        }
        muted("由上往下比對，第一條符合的規則決定走向。點一列查看詳情。區域網路一律直連。");
        LinearLayout actions = new LinearLayout(this);
        Button add = pillButton("新增規則", true);
        add.setOnClickListener(v -> editRule(-1, RouteRule.TARGET_PROXY));
        Button importList = pillButton("匯入清單", false);
        importList.setOnClickListener(v -> importMenu());
        actions.addView(add, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams spacer = new LinearLayout.LayoutParams(0, -2, 1);
        spacer.setMargins(dp(8), 0, 0, 0);
        actions.addView(importList, spacer);
        page.addView(actions);

        LinearLayout tools = new LinearLayout(this);
        tools.setPadding(0, dp(8), 0, 0);
        Button dryRun = pillButton("測試規則", false);
        dryRun.setOnClickListener(v -> showRuleDryRun());
        Button versions = pillButton("版本與回退", false);
        versions.setOnClickListener(v -> showRuleHistory());
        tools.addView(dryRun, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams toolSpacer = new LinearLayout.LayoutParams(0, -2, 1);
        toolSpacer.setMargins(dp(8), 0, 0, 0);
        tools.addView(versions, toolSpacer);
        page.addView(tools);

        int shown = Math.min(routing.rules.size(), MAX_RULE_ROWS);
        int enabledCount = ProxyRuleAdapter.enabledRules(routing).size();
        if (shown > 0) page.addView(sectionLabel("規則（啟用 " + enabledCount + "／全部 " + routing.rules.size() + "）"));
        for (int i = 0; i < shown; i++) {
            final int index = i;
            RouteRule rule = routing.rules.get(i);
            String first;
            String third;
            if (rule.match == RouteRule.Match.RULE_SET) {
                RuleSet set = sets.get(rule.value);
                first = set == null ? "已刪除的規則集" : set.name;
                third = set == null ? "這條規則不會生效" : set.count + " 條項目" + (set.updatable() ? " · 可自動更新" : "");
            } else {
                first = rule.value;
                third = (rule.enabled ? "" : "已停用 · ") + "#" + (i + 1);
            }
            listRow(first, (rule.enabled ? "" : "停用 · ") + rule.match.label + " → " + targetLabel(rule.target, profiles), third,
                    rule.enabled ? textColor() : mutedColor(), () -> ruleSheet(index));
        }
        if (routing.rules.size() > shown) muted("另有 " + (routing.rules.size() - shown) + " 條規則未列出（仍會生效）。");
        if (routing.rules.isEmpty()) muted("還沒有規則。可以新增一條，或從其他 App 分享網址清單到 Chris Proxy。");

        page.addView(sectionLabel("最後"));
        listRow("其餘所有流量（FINAL）", "→ " + targetLabel(routing.finalTarget, profiles), "沒有符合任何規則時",
                accentColor(), this::chooseFinal);
    }

    private void ruleSheet(int index) {
        RouteRule rule = routing.rules.get(index);
        RuleSet set = rule.match == RouteRule.Match.RULE_SET ? RuleSetStore.find(this, rule.value) : null;
        BottomSheetDialog sheet = sheet();
        LinearLayout body = sheetBody(sheet, set != null ? set.name : rule.value,
                (rule.enabled ? "啟用" : "停用") + " · " + rule.match.label + " → "
                        + targetLabel(rule.target, ProxyProfileStore.load(this)) + " · 第 " + (index + 1) + " 條");
        body.addView(card(0, rule.enabled ? "停用規則" : "啟用規則",
                rule.enabled ? "保留位置與內容，但不交給代理執行" : "重新加入代理的有序比對",
                CardStyle.TONAL, () -> {
                    sheet.dismiss();
                    ArrayList<RouteRule> rules = new ArrayList<>(routing.rules);
                    rules.set(index, rule.withEnabled(!rule.enabled));
                    saveRouting(routing.withRules(rules), rule.enabled ? "停用規則" : "啟用規則");
                    showRules();
                }));
        body.addView(card(0, "編輯", null, CardStyle.TONAL, () -> { sheet.dismiss(); editRule(index, rule.target); }));
        if (index > 0) {
            body.addView(card(0, "移到最上面", null, CardStyle.PLAIN, () -> { sheet.dismiss(); moveRule(index, 0); }));
            body.addView(card(0, "上移", null, CardStyle.PLAIN, () -> { sheet.dismiss(); moveRule(index, index - 1); }));
        }
        if (index < routing.rules.size() - 1) {
            body.addView(card(0, "下移", null, CardStyle.PLAIN, () -> { sheet.dismiss(); moveRule(index, index + 1); }));
        }
        body.addView(card(0, "刪除規則", set != null ? "規則集本身會保留" : null, CardStyle.DANGER, () -> {
            sheet.dismiss();
            ArrayList<RouteRule> rules = new ArrayList<>(routing.rules);
            rules.remove(index);
            saveRouting(routing.withRules(rules));
            showRules();
        }));
        sheet.show();
    }

    private void moveRule(int from, int to) {
        ArrayList<RouteRule> rules = new ArrayList<>(routing.rules);
        rules.add(to, rules.remove(from));
        saveRouting(routing.withRules(rules));
        showRules();
    }

    /** Opens the rule editor; index -1 adds a new rule at the top. */
    private void editRule(int index, String defaultTarget) {
        RouteRule existing = index >= 0 ? routing.rules.get(index) : null;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(8), dp(22), 0);

        ArrayList<RouteRule.Match> matches = new ArrayList<>();
        for (RouteRule.Match m : RouteRule.Match.values()) if (m != RouteRule.Match.RULE_SET) matches.add(m);
        List<RuleSet> sets = RuleSetStore.list(this);
        if (!sets.isEmpty()) matches.add(RouteRule.Match.RULE_SET);
        String[] matchLabels = new String[matches.size()];
        for (int i = 0; i < matches.size(); i++) matchLabels[i] = matches.get(i).label;
        form.addView(text("比對", 13, mutedColor()));
        Spinner matchSpinner = spinner(matchLabels);
        form.addView(matchSpinner);

        EditText value = field("", true);
        form.addView(value);
        String[] setLabels = new String[sets.size()];
        for (int i = 0; i < sets.size(); i++) setLabels[i] = sets.get(i).name + "（" + sets.get(i).count + "）";
        Spinner setSpinner = spinner(setLabels);
        form.addView(setSpinner);
        Button pickApp = new Button(this);
        pickApp.setText("從已安裝的 App 選擇");
        pickApp.setAllCaps(false);
        form.addView(pickApp);

        List<String[]> targets = targets();
        String[] targetLabels = new String[targets.size()];
        for (int i = 0; i < targets.size(); i++) targetLabels[i] = targets.get(i)[1];
        TextView targetCaption = text("去向", 13, mutedColor());
        targetCaption.setPadding(0, dp(12), 0, 0);
        form.addView(targetCaption);
        Spinner targetSpinner = spinner(targetLabels);
        form.addView(targetSpinner);

        matchSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                RouteRule.Match m = matches.get(position);
                value.setHint(hintFor(m));
                value.setVisibility(m == RouteRule.Match.RULE_SET ? View.GONE : View.VISIBLE);
                setSpinner.setVisibility(m == RouteRule.Match.RULE_SET ? View.VISIBLE : View.GONE);
                pickApp.setVisibility(m == RouteRule.Match.PACKAGE ? View.VISIBLE : View.GONE);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        pickApp.setOnClickListener(v -> pickApp(value));
        if (existing != null) {
            matchSpinner.setSelection(Math.max(0, matches.indexOf(existing.match)));
            if (existing.match == RouteRule.Match.RULE_SET) {
                for (int i = 0; i < sets.size(); i++) if (sets.get(i).id.equals(existing.value)) setSpinner.setSelection(i);
            } else {
                value.setText(existing.value);
            }
        } else {
            matchSpinner.setSelection(matches.indexOf(RouteRule.Match.DOMAIN_SUFFIX));
        }
        targetSpinner.setSelection(indexOfTarget(targets, existing != null ? existing.target : defaultTarget));

        AlertDialog d = dialog().setTitle(existing == null ? "新增規則" : "編輯規則")
                .setView(form).setPositiveButton("儲存", null).setNegativeButton("取消", null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                RouteRule.Match m = matches.get(matchSpinner.getSelectedItemPosition());
                String raw = m == RouteRule.Match.RULE_SET
                        ? sets.get(setSpinner.getSelectedItemPosition()).id : value.getText().toString();
                RouteRule rule = new RouteRule(m, raw, targets.get(targetSpinner.getSelectedItemPosition())[0],
                        existing == null || existing.enabled);
                ArrayList<RouteRule> rules = new ArrayList<>(routing.rules);
                if (index >= 0) rules.set(index, rule); else rules.add(0, rule);
                saveRouting(routing.withRules(rules));
                AppLogger.log(this, "INFO", (index >= 0 ? "更新" : "新增") + "規則=" + rule.match.name());
                d.dismiss();
                showRules();
            } catch (IllegalArgumentException e) {
                value.setError(e.getMessage());
            }
        }));
        d.show();
    }

    /** Local-only matcher preview. Values are not persisted and are deliberately not logged. */
    private void showRuleDryRun() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(8), dp(22), 0);
        form.addView(text("只在手機本機測試第一條符合的規則；不會連線或保存輸入。", 13, mutedColor()));
        EditText domain = field("網域，例如 ime.corp.example", true);
        EditText ip = field("目的 IP（可留空）", true);
        EditText packageName = field("App 套件名（可留空）", true);
        EditText port = field("目的埠（可留空）", true);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(domain);
        form.addView(ip);
        form.addView(packageName);
        form.addView(port);

        AlertDialog input = dialog().setTitle("Dry-run 測試規則").setView(form)
                .setPositiveButton("測試", null).setNegativeButton("取消", null).create();
        input.setOnShowListener(x -> input.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String d = domain.getText().toString().trim();
            String address = ip.getText().toString().trim();
            String pkg = packageName.getText().toString().trim();
            String portText = port.getText().toString().trim();
            if (d.isEmpty() && address.isEmpty() && pkg.isEmpty() && portText.isEmpty()) {
                domain.setError("至少輸入一個條件");
                return;
            }
            int destinationPort = 0;
            if (!portText.isEmpty()) {
                try {
                    destinationPort = Integer.parseInt(portText);
                    if (destinationPort < 1 || destinationPort > 65535) throw new NumberFormatException();
                } catch (NumberFormatException e) {
                    port.setError("埠號需為 1～65535");
                    return;
                }
            }
            ProxyRuleAdapter.Result result = ProxyRuleAdapter.dryRun(routing,
                    new ProxyRuleAdapter.Sample(d, address, pkg, destinationPort), RuleSetStore.loadEntries(this));
            input.dismiss();
            String title = result.rule == null ? "使用 FINAL" : "符合第 " + (result.ruleIndex + 1) + " 條";
            String message = result.explanation + "\n去向：" + targetLabel(result.target, ProxyProfileStore.load(this));
            dialog().setTitle(title).setMessage(message).setPositiveButton("知道了", null).show();
        }));
        input.show();
    }

    private void showRuleHistory() {
        List<RoutingStore.Version> history = RoutingStore.history(this);
        if (history.isEmpty()) {
            toast("目前是 v" + RoutingStore.currentVersion(this) + "，還沒有可回退的舊版本");
            return;
        }
        String[] labels = new String[history.size()];
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.ROOT);
        for (int i = 0; i < history.size(); i++) {
            RoutingStore.Version item = history.get(i);
            labels[i] = "v" + item.version + " · " + format.format(new java.util.Date(item.savedAt))
                    + " · " + item.routing.rules.size() + " 條 · " + item.reason;
        }
        dialog().setTitle("規則版本（目前 v" + RoutingStore.currentVersion(this) + "）")
                .setItems(labels, (d, which) -> {
                    RoutingStore.Version chosen = history.get(which);
                    dialog().setTitle("回退到 v" + chosen.version + "？")
                            .setMessage("目前設定會先自動保存，因此回退後仍可復原。")
                            .setPositiveButton("確認回退", (confirm, ignored) -> {
                                routing = RoutingStore.rollback(this, chosen.version);
                                applyChange();
                                toast("已回退；目前版本 v" + RoutingStore.currentVersion(this));
                                showRules();
                            })
                            .setNegativeButton("取消", null).show();
                }).setNegativeButton("關閉", null).show();
    }

    // --------------------------------------------------------- personal vocabulary

    private void showVocabulary() {
        enter(Screen.VOCABULARY, "個人詞庫");
        int enabled = VocabularyUiModel.enabledCount(vocabulary.entries);
        muted("離線保存的中立詞庫；可匯出給 Fcitx5／Rime。此版本不直接改寫正在使用的輸入法資料庫。");
        page.addView(sectionLabel("v" + vocabulary.version + " · 啟用 " + enabled + "／全部 "
                + vocabulary.entries.size() + " · 最多 " + VocabularyRepository.MAX_ENTRIES + " 條"));

        LinearLayout primary = new LinearLayout(this);
        Button add = pillButton("新增詞條", true);
        add.setOnClickListener(v -> editVocabulary(null));
        Button importText = pillButton("匯入文字", false);
        importText.setOnClickListener(v -> showVocabularyImport());
        primary.addView(add, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(0, -2, 1);
        gap.setMargins(dp(8), 0, 0, 0);
        primary.addView(importText, gap);
        page.addView(primary);

        LinearLayout secondary = new LinearLayout(this);
        secondary.setPadding(0, dp(8), 0, 0);
        Button export = pillButton("匯出詞庫", false);
        export.setOnClickListener(v -> showVocabularyExport());
        Button versions = pillButton("版本與回退", false);
        versions.setOnClickListener(v -> showVocabularyHistory());
        secondary.addView(export, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams secondGap = new LinearLayout.LayoutParams(0, -2, 1);
        secondGap.setMargins(dp(8), 0, 0, 0);
        secondary.addView(versions, secondGap);
        page.addView(secondary);

        LinearLayout search = new LinearLayout(this);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(0, dp(12), 0, 0);
        EditText query = field("搜尋詞條或讀音", true);
        query.setText(vocabularyQuery);
        query.setSingleLine(true);
        Button searchButton = pillButton(vocabularyQuery.isEmpty() ? "搜尋" : "清除", false);
        searchButton.setOnClickListener(v -> {
            vocabularyQuery = vocabularyQuery.isEmpty() ? query.getText().toString().trim() : "";
            showVocabulary();
        });
        search.addView(query, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams searchButtonParams = new LinearLayout.LayoutParams(dp(86), -2);
        searchButtonParams.setMargins(dp(8), 0, 0, 0);
        search.addView(searchButton, searchButtonParams);
        page.addView(search);

        List<VocabularyEntry> visible = VocabularyUiModel.filter(vocabulary.entries, vocabularyQuery);
        int shown = Math.min(visible.size(), MAX_RULE_ROWS);
        if (!vocabularyQuery.isEmpty()) page.addView(sectionLabel("搜尋結果 " + visible.size() + " 條"));
        for (int i = 0; i < shown; i++) {
            VocabularyEntry entry = visible.get(i);
            listRow(entry.term, VocabularyUiModel.detail(entry), "#" + (i + 1),
                    entry.enabled ? textColor() : mutedColor(), () -> vocabularySheet(entry));
        }
        if (visible.size() > shown) muted("另有 " + (visible.size() - shown) + " 條未顯示；可用搜尋縮小範圍。");
        if (visible.isEmpty()) muted(vocabulary.entries.isEmpty()
                ? "還沒有詞條。可先新增常用術語，或貼上 Rime／TSV 詞庫。"
                : "沒有符合搜尋條件的詞條。");
    }

    private void vocabularySheet(VocabularyEntry entry) {
        BottomSheetDialog sheet = sheet();
        LinearLayout body = sheetBody(sheet, entry.term, VocabularyUiModel.detail(entry));
        body.addView(card(0, entry.enabled ? "停用詞條" : "啟用詞條",
                entry.enabled ? "匯出時略過，但保留內容與版本" : "重新納入 Fcitx／Rime 匯出",
                CardStyle.TONAL, () -> {
                    sheet.dismiss();
                    ArrayList<VocabularyEntry> entries = new ArrayList<>(vocabulary.entries);
                    int index = vocabularyIndex(entry.id);
                    if (index >= 0) entries.set(index, entry.withEnabled(!entry.enabled));
                    saveVocabulary(entries, entry.enabled ? "停用詞條" : "啟用詞條");
                }));
        body.addView(card(0, "編輯", null, CardStyle.PLAIN, () -> { sheet.dismiss(); editVocabulary(entry); }));
        body.addView(card(0, "刪除詞條", "刪除前會保留可回退版本", CardStyle.DANGER, () -> {
            sheet.dismiss();
            dialog().setTitle("刪除這個詞條？")
                    .setMessage("可從「版本與回退」復原。")
                    .setPositiveButton("刪除", (d, w) -> {
                        ArrayList<VocabularyEntry> entries = new ArrayList<>(vocabulary.entries);
                        entries.removeIf(item -> item.id.equals(entry.id));
                        saveVocabulary(entries, "刪除詞條");
                    }).setNegativeButton("取消", null).show();
        }));
        sheet.show();
    }

    private void editVocabulary(VocabularyEntry existing) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(8), dp(22), 0);
        EditText term = field("詞條，例如 CCTV盲咗", true);
        EditText reading = field("注音／Rime 編碼（可留空）", true);
        EditText weight = field("權重 1～1000000", true);
        weight.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (existing != null) {
            term.setText(existing.term);
            reading.setText(existing.reading);
            weight.setText(String.valueOf(existing.weight));
        } else {
            weight.setText("100");
        }
        form.addView(term);
        form.addView(reading);
        form.addView(weight);
        AlertDialog editor = dialog().setTitle(existing == null ? "新增詞條" : "編輯詞條")
                .setView(form).setPositiveButton("儲存", null).setNegativeButton("取消", null).create();
        editor.setOnShowListener(x -> editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int parsedWeight = Integer.parseInt(weight.getText().toString().trim());
                VocabularyEntry changed = existing == null
                        ? VocabularyEntry.create(term.getText().toString(), reading.getText().toString(), parsedWeight)
                        : existing.edited(term.getText().toString(), reading.getText().toString(), parsedWeight);
                ArrayList<VocabularyEntry> entries = new ArrayList<>(vocabulary.entries);
                int index = existing == null ? -1 : vocabularyIndex(existing.id);
                ensureVocabularyUnique(entries, changed, index);
                if (index >= 0) entries.set(index, changed); else entries.add(0, changed);
                editor.dismiss();
                saveVocabulary(entries, existing == null ? "新增詞條" : "編輯詞條");
            } catch (NumberFormatException e) {
                weight.setError("請輸入數字權重");
            } catch (IllegalArgumentException e) {
                term.setError(e.getMessage());
            }
        }));
        editor.show();
    }

    private void showVocabularyImport() {
        EditText input = field("每行：詞條 [Tab] 讀音／編碼 [Tab] 權重", false);
        input.setMinLines(8);
        input.setGravity(Gravity.TOP);
        AlertDialog importer = dialog().setTitle("匯入 Rime／TSV 詞庫")
                .setMessage("支援一行一詞及 Rime 字典正文；先預覽數量，再決定是否寫入。")
                .setView(padded(input)).setPositiveButton("預覽", null).setNegativeButton("取消", null).create();
        importer.setOnShowListener(x -> importer.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                VocabularyImporter.Result result = VocabularyImporter.merge(vocabulary.entries, input.getText().toString());
                if (result.added == 0 && result.updated == 0) {
                    input.setError("沒有可新增或更新的詞條；略過 " + result.skipped + " 行");
                    return;
                }
                importer.dismiss();
                dialog().setTitle("確認匯入？")
                        .setMessage("新增 " + result.added + " 條 · 更新 " + result.updated
                                + " 條 · 略過 " + result.skipped + " 行\n\n匯入前版本會保留，可隨時回退。")
                        .setPositiveButton("匯入", (d, w) -> saveVocabulary(result.entries, "匯入詞庫"))
                        .setNegativeButton("取消", null).show();
            } catch (IllegalArgumentException e) {
                input.setError(e.getMessage());
            }
        }));
        importer.show();
    }

    private void showVocabularyExport() {
        if (VocabularyUiModel.enabledCount(vocabulary.entries) == 0) { toast("沒有已啟用的詞條可匯出"); return; }
        String[] formats = {"複製 Rime YAML", "複製 Fcitx TSV"};
        dialog().setTitle("匯出已啟用詞條")
                .setMessage("明確匯出時才會把個人詞條放到系統剪貼簿。")
                .setItems(formats, (d, which) -> {
                    String value = which == 0 ? FcitxVocabularyAdapter.toRimeYaml(vocabulary.entries)
                            : FcitxVocabularyAdapter.toFcitxTsv(vocabulary.entries);
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText(which == 0 ? "Rime 個人詞庫" : "Fcitx 個人詞庫", value));
                    toast("已複製 " + VocabularyUiModel.enabledCount(vocabulary.entries) + " 條；請貼到目標詞庫檔案");
                }).setNegativeButton("取消", null).show();
    }

    private void showVocabularyHistory() {
        List<VocabularyCodec.Snapshot> history = VocabularyStore.history(this);
        if (history.isEmpty()) { toast("目前是 v" + vocabulary.version + "，還沒有可回退版本"); return; }
        String[] labels = new String[history.size()];
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.ROOT);
        for (int i = 0; i < history.size(); i++) {
            VocabularyCodec.Snapshot item = history.get(i);
            labels[i] = "v" + item.version + " · " + format.format(new java.util.Date(item.savedAt))
                    + " · " + item.entries.size() + " 條 · " + item.reason;
        }
        dialog().setTitle("詞庫版本（目前 v" + vocabulary.version + "）")
                .setItems(labels, (d, which) -> {
                    VocabularyCodec.Snapshot chosen = history.get(which);
                    dialog().setTitle("回退到詞庫 v" + chosen.version + "？")
                            .setMessage("目前詞庫會先自動保存，回退後仍可復原。")
                            .setPositiveButton("確認回退", (confirm, ignored) -> {
                                vocabulary = VocabularyStore.rollback(this, chosen.version);
                                vocabularyQuery = "";
                                AppLogger.log(this, "INFO", "本機詞庫已回退，條數=" + vocabulary.entries.size());
                                showVocabulary();
                            }).setNegativeButton("取消", null).show();
                }).setNegativeButton("關閉", null).show();
    }

    private void saveVocabulary(List<VocabularyEntry> entries, String reason) {
        try {
            vocabulary = VocabularyStore.save(this, entries, reason);
            AppLogger.log(this, "INFO", "本機詞庫已更新，條數=" + vocabulary.entries.size());
            showVocabulary();
        } catch (Exception e) {
            toast("詞庫保存失敗：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private int vocabularyIndex(String id) {
        for (int i = 0; i < vocabulary.entries.size(); i++) if (vocabulary.entries.get(i).id.equals(id)) return i;
        return -1;
    }

    private static void ensureVocabularyUnique(List<VocabularyEntry> entries, VocabularyEntry changed, int ignoreIndex) {
        for (int i = 0; i < entries.size(); i++) {
            if (i != ignoreIndex && entries.get(i).dedupeKey().equals(changed.dedupeKey())) {
                throw new IllegalArgumentException("相同詞條與讀音已存在");
            }
        }
    }

    private static String hintFor(RouteRule.Match match) {
        switch (match) {
            case DOMAIN: return "例如 www.example.com";
            case DOMAIN_SUFFIX: return "例如 example.com（含所有子網域）";
            case DOMAIN_KEYWORD: return "例如 example";
            case IP_CIDR: return "例如 10.8.0.0/16 或 2001:db8::/32";
            case PACKAGE: return "例如 com.example.app";
            case PORT: return "例如 443、8000-8080 或 80,443";
            default: return "";
        }
    }

    private void pickApp(EditText target) {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(launcher, 0);
        ArrayList<String[]> apps = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (ResolveInfo info : found) {
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            apps.add(new String[]{pkg, String.valueOf(info.loadLabel(pm))});
        }
        apps.sort((a, b) -> a[1].compareToIgnoreCase(b[1]));
        String[] labels = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) labels[i] = apps.get(i)[1] + "\n" + apps.get(i)[0];
        dialog().setTitle("選擇 App")
                .setItems(labels, (d, which) -> target.setText(apps.get(which)[0]))
                .setNegativeButton("取消", null).show();
    }

    private void chooseFinal() {
        chooseTarget("其餘所有流量走向", routing.finalTarget, target -> {
            saveRouting(routing.withFinal(target));
            showRules();
        });
    }

    private interface TargetChoice { void chosen(String target); }

    private void chooseTarget(String heading, String current, TargetChoice choice) {
        List<String[]> targets = targets();
        String[] labels = new String[targets.size()];
        for (int i = 0; i < targets.size(); i++) labels[i] = targets.get(i)[1];
        dialog().setTitle(heading)
                .setSingleChoiceItems(labels, indexOfTarget(targets, current), (d, which) -> {
                    d.dismiss();
                    choice.chosen(targets.get(which)[0]);
                })
                .setNegativeButton("取消", null).show();
    }

    /** [target, label] pairs: direct, node in use, block, then every saved node. */
    private List<String[]> targets() {
        ArrayList<String[]> result = new ArrayList<>();
        result.add(new String[]{RouteRule.TARGET_DIRECT, "直連"});
        result.add(new String[]{RouteRule.TARGET_PROXY, "目前使用的節點"});
        result.add(new String[]{RouteRule.TARGET_BLOCK, "封鎖"});
        for (ProxyProfile profile : ProxyProfileStore.load(this)) {
            result.add(new String[]{RouteRule.nodeTarget(profile.id()), "節點：" + profile.label});
        }
        return result;
    }

    private static int indexOfTarget(List<String[]> targets, String target) {
        for (int i = 0; i < targets.size(); i++) if (targets.get(i)[0].equals(target)) return i;
        return 0;
    }

    private static String targetLabel(String target, List<ProxyProfile> profiles) {
        if (RouteRule.TARGET_DIRECT.equals(target)) return "直連";
        if (RouteRule.TARGET_BLOCK.equals(target)) return "封鎖";
        if (RouteRule.TARGET_PROXY.equals(target)) return "目前節點";
        String id = target.substring("node:".length());
        for (ProxyProfile profile : profiles) if (profile.id().equals(id)) return profile.label;
        return "已刪除的節點（暫走目前節點）";
    }

    // ------------------------------------------------------------ rule sets

    private Map<String, RuleSet> ruleSetMap() {
        HashMap<String, RuleSet> result = new HashMap<>();
        for (RuleSet set : RuleSetStore.list(this)) result.put(set.id, set);
        return result;
    }

    private void showRuleSets() {
        enter(Screen.RULE_SETS, "規則集");
        List<RuleSet> sets = RuleSetStore.list(this);
        muted("規則集是一份網域／IP 清單，由一條規則決定整份清單的走向。來自網址的清單每天自動更新，右上角可立即更新。");
        page.addView(card(0, "匯入清單", "網址、剪貼簿、檔案，或從其他 App 分享過來", CardStyle.PRIMARY, this::importMenu));
        if (sets.isEmpty()) return;
        page.addView(sectionLabel("已保存（" + sets.size() + "）"));
        List<ProxyProfile> profiles = ProxyProfileStore.load(this);
        for (RuleSet set : sets) {
            StringBuilder used = new StringBuilder();
            for (RouteRule rule : routing.rules) {
                if (rule.match == RouteRule.Match.RULE_SET && rule.value.equals(set.id)) {
                    if (used.length() > 0) used.append("、");
                    used.append(targetLabel(rule.target, profiles));
                }
            }
            String third = !set.error.isEmpty() ? "更新失敗：" + set.error
                    : set.updatable() ? "更新於 " + ago(set.updatedAt) + " · " + Uri.parse(set.url).getHost()
                    : "本機清單 · 匯入於 " + ago(set.updatedAt);
            listRow(set.name, set.count + " 條 · " + (used.length() == 0 ? "未使用" : "→ " + used),
                    third, set.error.isEmpty() ? textColor() : dangerColor(), () -> ruleSetSheet(set));
        }
    }

    private void ruleSetSheet(RuleSet set) {
        BottomSheetDialog sheet = sheet();
        LinearLayout body = sheetBody(sheet, set.name, set.updatable() ? set.url : set.count + " 條項目，本機清單");
        boolean used = false;
        for (RouteRule rule : routing.rules) if (rule.match == RouteRule.Match.RULE_SET && rule.value.equals(set.id)) used = true;
        if (!used) {
            body.addView(card(0, "加入分流規則", "選擇整份清單的走向", CardStyle.PRIMARY, () -> {
                sheet.dismiss();
                chooseTarget("「" + set.name + "」的走向", RouteRule.TARGET_PROXY, target -> addRuleSetRule(set, target));
            }));
        }
        if (set.updatable()) {
            body.addView(card(0, "立即更新", "上次：" + ago(set.updatedAt), CardStyle.TONAL, () -> {
                sheet.dismiss();
                refreshRuleSets(Collections.singletonList(set));
            }));
        }
        body.addView(card(0, "重新命名", null, CardStyle.PLAIN, () -> {
            sheet.dismiss();
            EditText input = field("名稱", true);
            input.setText(set.name);
            dialog().setTitle("重新命名").setView(padded(input))
                    .setPositiveButton("儲存", (d, w) -> {
                        RuleSetStore.update(this, set.withName(input.getText().toString()));
                        showRuleSets();
                    })
                    .setNegativeButton("取消", null).show();
        }));
        body.addView(card(0, "刪除規則集", "使用它的規則也會一併移除", CardStyle.DANGER, () -> {
            sheet.dismiss();
            dialog().setTitle("刪除「" + set.name + "」？")
                    .setPositiveButton("刪除", (d, w) -> {
                        ArrayList<RouteRule> rules = new ArrayList<>();
                        for (RouteRule rule : routing.rules) {
                            if (!(rule.match == RouteRule.Match.RULE_SET && rule.value.equals(set.id))) rules.add(rule);
                        }
                        RuleSetStore.delete(this, set.id);
                        saveRouting(routing.withRules(rules));
                        showRuleSets();
                    })
                    .setNegativeButton("取消", null).show();
        }));
        sheet.show();
    }

    private void addRuleSetRule(RuleSet set, String target) {
        ArrayList<RouteRule> rules = new ArrayList<>(routing.rules);
        rules.add(new RouteRule(RouteRule.Match.RULE_SET, set.id, target));
        saveRouting(routing.withRules(rules));
        AppLogger.log(this, "INFO", "規則集加入規則=" + set.id);
        showRules();
    }

    private void refreshAllRuleSets() {
        ArrayList<RuleSet> updatable = new ArrayList<>();
        for (RuleSet set : RuleSetStore.list(this)) if (set.updatable()) updatable.add(set);
        if (updatable.isEmpty()) { toast("沒有來自網址的規則集"); return; }
        refreshRuleSets(updatable);
    }

    private void refreshStaleRuleSets() {
        ArrayList<RuleSet> stale = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (RuleSet set : RuleSetStore.list(this)) if (set.stale(now)) stale.add(set);
        if (!stale.isEmpty()) refreshRuleSets(stale, false);
    }

    private void refreshRuleSets(List<RuleSet> sets) {
        refreshRuleSets(sets, true);
    }

    private void refreshRuleSets(List<RuleSet> sets, boolean announce) {
        if (announce) toast("正在更新 " + sets.size() + " 個規則集…");
        new Thread(() -> {
            int ok = 0;
            for (RuleSet set : sets) if (RuleSetStore.refresh(this, set).error.isEmpty()) ok++;
            final int succeeded = ok;
            runOnUiThread(() -> {
                applyChange();
                if (announce) toast("已更新 " + succeeded + " / " + sets.size() + " 個規則集");
                if (screen == Screen.RULE_SETS) showRuleSets();
                else if (screen == Screen.HOME) showHome();
            });
        }, "rule-set-refresh").start();
    }

    private static String ago(long time) {
        long minutes = Math.max(0, (System.currentTimeMillis() - time) / 60000);
        if (minutes < 1) return "剛剛";
        if (minutes < 60) return minutes + " 分鐘前";
        if (minutes < 60 * 24) return (minutes / 60) + " 小時前";
        return (minutes / 60 / 24) + " 天前";
    }

    // --------------------------------------------------------------- import

    /** A list waiting for confirmation: from a URL (updatable) or plain text. */
    private static final class ImportDraft {
        final String name;
        final String url;
        final RuleListParser.Parsed parsed;
        final String sourceLabel;
        ImportDraft(String name, String url, RuleListParser.Parsed parsed, String sourceLabel) {
            this.name = name;
            this.url = url;
            this.parsed = parsed;
            this.sourceLabel = sourceLabel;
        }
    }

    private void importMenu() {
        String[] items = {"從網址匯入（可自動更新）", "從剪貼簿貼上", "從檔案匯入", "Loon / Surge 設定檔的 [Rule]"};
        dialog().setTitle("匯入清單")
                .setItems(items, (d, which) -> {
                    if (which == 0) importUrlDialog("");
                    else if (which == 1) importClipboard();
                    else if (which == 2) openFile(REQUEST_LIST_FILE);
                    else openFile(REQUEST_RULE_FILE);
                })
                .setNegativeButton("取消", null).show();
    }

    private void importUrlDialog(String prefill) {
        EditText input = field("https://example.com/list.txt", true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(prefill);
        dialog().setTitle("從網址匯入")
                .setMessage("支援純網域、Surge/Clash/Loon 規則清單、Clash payload、Adblock 與 hosts 格式。")
                .setView(padded(input))
                .setPositiveButton("下載預覽", (d, w) -> fetchForImport(input.getText().toString().trim()))
                .setNegativeButton("取消", null).show();
    }

    private void fetchForImport(String url) {
        toast("正在下載清單…");
        new Thread(() -> {
            try {
                RuleListParser.Parsed parsed = RuleListParser.parse(RuleSetStore.download(url));
                runOnUiThread(() -> showImport(new ImportDraft(nameFromUrl(url), url, parsed, url)));
            } catch (Exception e) {
                AppLogger.log(this, "WARN", "清單下載失敗=" + e.getClass().getSimpleName());
                runOnUiThread(() -> toast("下載失敗：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
            }
        }, "list-download").start();
    }

    private void importClipboard() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        CharSequence value = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).coerceToText(this);
        if (value == null || value.toString().trim().isEmpty()) { toast("剪貼簿是空的"); return; }
        importText(value.toString(), "剪貼簿");
    }

    /** Text that is one URL is fetched; anything else is parsed as the list itself. */
    private void importText(String text, String source) {
        String trimmed = text.trim();
        // Shared config JSON or a transfer link is a config import, not a domain list.
        if (looksLikeConfig(trimmed.getBytes(StandardCharsets.UTF_8)) || TransferClient.isTransferQr(trimmed)) {
            showSettings();
            importConfigBytes(trimmed.getBytes(StandardCharsets.UTF_8), source);
            return;
        }
        if (trimmed.matches("(?i)https?://\\S+") ) {
            importUrlDialog(trimmed);
            return;
        }
        showImport(new ImportDraft("來自" + source, "", RuleListParser.parse(trimmed), source));
    }

    /**
     * Share sheet, "open with" and chrisproxy://import?url=… all land here.
     * Nothing is applied until the user confirms on the preview screen.
     */
    private boolean handleExternalIntent(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        try {
            if (Intent.ACTION_SEND.equals(action)) {
                Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (stream != null) { importFile(stream, "分享的檔案"); return true; }
                String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (shared != null && !shared.trim().isEmpty()) { importText(shared, "分享"); return true; }
            } else if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
                Uri data = intent.getData();
                if ("chrisproxy".equals(data.getScheme()) && "transfer".equals(data.getHost())) {
                    showSettings();
                    startLiveImport(data.toString());
                    return true;
                }
                if ("chrisproxy".equals(data.getScheme()) && "config".equals(data.getHost())) {
                    String url = data.getQueryParameter("url");
                    showSettings();
                    if (url != null) importConfigUrl(url);
                    return true;
                }
                if ("chrisproxy".equals(data.getScheme())) {
                    String url = data.getQueryParameter("url");
                    if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
                        toast("匯入連結缺少有效的清單網址");
                        return false;
                    }
                    showHome();
                    importUrlDialog(url);
                    return true;
                }
                importFile(data, "開啟的檔案");
                return true;
            }
        } catch (Exception e) {
            AppLogger.log(this, "WARN", "外部匯入失敗=" + e.getClass().getSimpleName());
            toast("無法讀取分享的內容");
        }
        return false;
    }

    private void importFile(Uri uri, String source) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new java.io.IOException("無法開啟檔案");
            byte[] buffer = new byte[16384];
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = input.read(buffer)) != -1) {
                if (bytes.size() + n > RuleSetStore.MAX_DOWNLOAD_BYTES) throw new java.io.IOException("檔案超過 8 MB");
                bytes.write(buffer, 0, n);
            }
            if (looksLikeConfig(bytes.toByteArray())) {
                showSettings();
                importConfigBytes(bytes.toByteArray(), source);
                return;
            }
            String name = uri.getLastPathSegment() == null ? source : uri.getLastPathSegment();
            int slash = name.lastIndexOf('/');
            if (slash >= 0) name = name.substring(slash + 1);
            showImport(new ImportDraft(name.replaceAll("\\.(txt|list|conf|yaml|yml)$", ""), "",
                    RuleListParser.parse(bytes.toString("UTF-8")), source));
        } catch (Exception e) {
            toast("匯入失敗：" + e.getMessage());
        }
    }

    private void showImport(ImportDraft draft) {
        importDraft = draft;
        enter(Screen.IMPORT, "匯入清單");
        RuleListParser.Parsed parsed = draft.parsed;
        LinearLayout summary = surfaceBlock();
        TextView count = text(parsed.size() + " 條", 24, textColor());
        count.setTypeface(null, Typeface.BOLD);
        summary.addView(count);
        summary.addView(text(parsed.breakdown(), 14, mutedColor()));
        if (parsed.skipped > 0) summary.addView(text("略過 " + parsed.skipped + " 行不支援的內容", 13, mutedColor()));
        if (parsed.truncated) summary.addView(text("清單過大，只讀取前 " + RuleListParser.MAX_ENTRIES + " 條", 13, amberColor()));
        summary.addView(text("來源：" + draft.sourceLabel, 12, mutedColor()));
        page.addView(summary);
        if (parsed.size() == 0) {
            muted("沒有可匯入的網域或 IP。支援純網域、Surge/Clash/Loon 規則、Clash payload、Adblock、hosts 格式。");
            return;
        }

        page.addView(sectionLabel("名稱"));
        EditText name = field("清單名稱", true);
        name.setText(draft.name);
        page.addView(name);
        page.addView(sectionLabel("走向"));
        List<String[]> targets = targets();
        String[] labels = new String[targets.size()];
        for (int i = 0; i < targets.size(); i++) labels[i] = targets.get(i)[1];
        Spinner target = spinner(labels);
        target.setSelection(indexOfTarget(targets, RouteRule.TARGET_PROXY));
        page.addView(target);

        page.addView(card(0, "存成規則集", draft.url.isEmpty()
                ? "整份清單用一條規則管理，效能最好"
                : "整份清單用一條規則管理，每天自動從網址更新", CardStyle.PRIMARY, () -> {
            try {
                RuleSet set = RuleSetStore.create(this, name.getText().toString(), draft.url, parsed);
                importDraft = null;
                addRuleSetRule(set, targets.get(target.getSelectedItemPosition())[0]);
                toast("已加入規則集「" + set.name + "」");
            } catch (Exception e) {
                toast("保存失敗：" + e.getMessage());
            }
        }));
        if (parsed.size() <= MAX_SPLIT_RULES) {
            page.addView(card(0, "拆成個別規則", "每一條都能單獨調整順序與走向；不會自動更新", CardStyle.PLAIN, () -> {
                String chosen = targets.get(target.getSelectedItemPosition())[0];
                ArrayList<RouteRule> rules = new ArrayList<>(routing.rules);
                for (String v : parsed.suffixes) rules.add(new RouteRule(RouteRule.Match.DOMAIN_SUFFIX, v, chosen));
                for (String v : parsed.domains) rules.add(new RouteRule(RouteRule.Match.DOMAIN, v, chosen));
                for (String v : parsed.keywords) rules.add(new RouteRule(RouteRule.Match.DOMAIN_KEYWORD, v, chosen));
                for (String v : parsed.cidrs) rules.add(new RouteRule(RouteRule.Match.IP_CIDR, v, chosen));
                importDraft = null;
                saveRouting(routing.withRules(rules));
                AppLogger.log(this, "INFO", "清單拆成規則，條數=" + parsed.size());
                toast("已加入 " + parsed.size() + " 條規則");
                showRules();
            }));
        }
        page.addView(card(0, "取消", null, CardStyle.PLAIN, () -> { importDraft = null; showRules(); }));
    }

    private static String nameFromUrl(String url) {
        Uri uri = Uri.parse(url);
        String last = uri.getLastPathSegment();
        if (last != null && !last.isEmpty()) return last.replaceAll("\\.(txt|list|conf|yaml|yml)$", "");
        return uri.getHost() == null ? "網址清單" : uri.getHost();
    }

    private void importLoon(Uri uri) {
        try (InputStreamReader reader = new InputStreamReader(getContentResolver().openInputStream(uri), StandardCharsets.UTF_8)) {
            LoonConfigParser.Result result = LoonConfigParser.parse(reader);
            if (result.rules.isEmpty() && result.finalTarget == null) { toast("檔案中沒有可匯入的規則"); return; }
            String message = "可匯入 " + result.rules.size() + " 條規則"
                    + (result.skipped > 0 ? "，略過 " + result.skipped + " 條不支援的項目" : "")
                    + (result.finalTarget != null ? "\nFINAL → " + targetLabel(result.finalTarget, ProxyProfileStore.load(this)) : "")
                    + "\n\n指定其他策略名稱的規則會走目前使用的節點。";
            AlertDialog.Builder builder = dialog().setTitle("匯入規則").setMessage(message)
                    .setPositiveButton("加到現有規則之後", (d, w) -> applyLoon(result, false))
                    .setNegativeButton("取消", null);
            if (!routing.rules.isEmpty()) builder.setNeutralButton("取代現有規則", (d, w) -> applyLoon(result, true));
            builder.show();
        } catch (Exception e) {
            AppLogger.log(this, "ERROR", "規則檔匯入失敗=" + e.getClass().getSimpleName());
            toast("匯入失敗：" + e.getMessage());
        }
    }

    private void applyLoon(LoonConfigParser.Result result, boolean replace) {
        ArrayList<RouteRule> rules = replace ? new ArrayList<>() : new ArrayList<>(routing.rules);
        rules.addAll(result.rules);
        Routing next = routing.withRules(rules);
        if (result.finalTarget != null) next = next.withFinal(result.finalTarget);
        saveRouting(next);
        AppLogger.log(this, "INFO", "匯入規則檔，條數=" + result.rules.size());
        showRules();
    }

    // ------------------------------------------------------------- settings

    private void showSettings() {
        enter(Screen.SETTINGS, "設定");
        page.addView(sectionLabel("這支手機"));
        page.addView(card(0, "名稱：" + deviceName(), "首頁問候與掃碼轉移時顯示", CardStyle.PLAIN, this::editDeviceName));

        page.addView(sectionLabel("外觀"));
        String[] modes = {"system", "light", "dark"};
        Spinner mode = spinner(new String[]{"跟隨系統", "始終淺色", "始終深色"});
        mode.setSelection(Math.max(0, java.util.Arrays.asList(modes).indexOf(themeMode())));
        page.addView(labelled("深淺色", mode));
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (modes[position].equals(themeMode())) return;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_THEME_MODE, modes[position]).apply();
                applyThemePreference();
                recreate();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        page.addView(sectionLabel("Wi-Fi"));
        page.addView(card(0, "在這些 Wi-Fi 全部直連", policy.directSsids.isEmpty()
                ? "例如公司或家裡的網路；離開後自動恢復代理" : policy.directSsids.size() + " 個網路；離開後自動恢復代理",
                CardStyle.PLAIN, this::showSsids));

        page.addView(sectionLabel("DNS"));
        page.addView(card(0, "App 內私人 DNS", policy.privateDns.isEmpty() ? "使用系統 DNS" : policy.privateDns,
                CardStyle.PLAIN, this::editPrivateDns));

        page.addView(sectionLabel("廣告過濾"));
        page.addView(card(0, "廣告網域清單", policy.blockedDomains.isEmpty()
                ? "未啟用；只封鎖網域，不解密 HTTPS" : policy.blockedDomains.size() + " 個網域會被封鎖",
                CardStyle.PLAIN, this::adblockActions));

        page.addView(sectionLabel("App 設定檔"));
        page.addView(card(0, "匯入設定", "掃描另一支手機、文件、JSON、瀏覽器／網址", CardStyle.PLAIN, this::importConfigMenu));
        page.addView(card(0, "導出設定", "傳到另一支手機、加密設定檔、JSON；需要驗證本人", CardStyle.PLAIN, this::exportConfigMenu));
        page.addView(card(0, "導出全部節點（明文）", "只含節點；需要驗證本人", CardStyle.PLAIN,
                () -> ExportGate.authenticate(this, "導出全部節點", this::exportProfiles)));

        page.addView(sectionLabel("診斷"));
        page.addView(card(0, "分享運行記錄", "App 與核心記錄，不含節點原文或密鑰", CardStyle.PLAIN, this::shareLogs));

        page.addView(sectionLabel("關於"));
        page.addView(card(0, "檢查更新", "目前版本 " + versionName(), CardStyle.PLAIN, () -> new ProxyUpdater(this).check()));
        page.addView(card(0, "更新伺服器", UpdatePolicy.getUpdateUrl(this).isEmpty() ? "尚未設定" : UpdatePolicy.getUpdateUrl(this),
                CardStyle.PLAIN, this::editUpdateServer));
        TextView version = text("Chris Proxy " + versionName(), 14, mutedColor());
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, dp(12), 0, dp(12));
        page.addView(version);
    }

    private void editUpdateServer() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(UpdatePolicy.getUpdateUrl(this));
        input.setHint("https://...");
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("更新伺服器網址")
                .setMessage("請輸入更新 manifest 的 HTTPS 網址。")
                .setView(input)
                .setPositiveButton("儲存", (d, w) -> {
                    String value = input.getText().toString().trim();
                    if (!value.isEmpty()) {
                        try {
                            UpdatePolicy.checkedUrl(value);
                        } catch (Exception e) {
                            toast("網址無效：" + e.getMessage());
                            return;
                        }
                    }
                    UpdatePolicy.setUpdateUrl(this, value);
                    if (screen == Screen.SETTINGS) showSettings();
                    toast("更新伺服器網址已儲存");
                })
                .setNeutralButton("立即檢查", (d, w) -> {
                    String value = input.getText().toString().trim();
                    if (!value.isEmpty()) {
                        try {
                            UpdatePolicy.checkedUrl(value);
                            UpdatePolicy.setUpdateUrl(this, value);
                        } catch (Exception e) {
                            toast("網址無效：" + e.getMessage());
                            return;
                        }
                    }
                    new ProxyUpdater(this).check(value);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private View labelled(String label, View field) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(4), dp(8), dp(4));
        row.addView(text(label, 15, textColor()), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(field);
        return row;
    }

    private void showSsids() {
        enter(Screen.SSIDS, "Wi-Fi 例外");
        muted("VPN 保持開啟：連上這些 Wi-Fi 時所有流量自動直連，關掉 Wi-Fi 或換到其他網路就自動恢復代理，不用手動切換。名稱需完全一致（區分大小寫），且需要位置權限才能讀取 Wi-Fi 名稱。");
        String current = SsidResolver.currentSsid(this);
        if (!current.startsWith("<")) {
            page.addView(card(0, "加入目前的 Wi-Fi", current, CardStyle.PRIMARY, () -> addSsid(current)));
        } else {
            page.addView(card(0, "讀不到目前的 Wi-Fi", "需要位置權限才能讀取 Wi-Fi 名稱；不會取得或上傳位置",
                    CardStyle.WARNING, this::requestWifiPermissions));
        }
        page.addView(card(0, "手動輸入 Wi-Fi 名稱", null, CardStyle.TONAL, () -> {
            EditText input = field("Wi-Fi 名稱（SSID）", true);
            dialog().setTitle("新增 Wi-Fi 例外").setView(padded(input))
                    .setPositiveButton("新增", (d, w) -> addSsid(input.getText().toString()))
                    .setNegativeButton("取消", null).show();
        }));
        if (!policy.directSsids.isEmpty()) page.addView(sectionLabel("已設定"));
        for (String ssid : policy.directSsids) {
            listRow(ssid, "不使用代理", null, textColor(), () ->
                    dialog().setTitle("移除「" + ssid + "」？")
                            .setPositiveButton("移除", (d, w) -> {
                                LinkedHashSet<String> ssids = new LinkedHashSet<>(policy.directSsids);
                                ssids.remove(ssid);
                                savePolicy(withSsids(ssids));
                                showSsids();
                            })
                            .setNegativeButton("取消", null).show());
        }
    }

    private void addSsid(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) { toast("Wi-Fi 名稱不可為空"); return; }
        LinkedHashSet<String> ssids = new LinkedHashSet<>(policy.directSsids);
        ssids.add(value);
        savePolicy(withSsids(ssids));
        showSsids();
    }

    private Policy withSsids(LinkedHashSet<String> ssids) {
        return new Policy(policy.mode, ssids, policy.directDomains, policy.proxyDomains,
                policy.blockedDomains, policy.privateDns);
    }

    private Policy withBlocked(LinkedHashSet<String> blocked) {
        return new Policy(policy.mode, policy.directSsids, policy.directDomains,
                policy.proxyDomains, blocked, policy.privateDns);
    }

    private void editPrivateDns() {
        EditText input = field("https://dns.example/dns-query 或 tls://dns.example", true);
        input.setText(policy.privateDns);
        AlertDialog d = dialog().setTitle("App 內私人 DNS")
                .setMessage("只影響經過本 App 的流量，不會更改系統的私人 DNS。留空使用系統 DNS。")
                .setView(padded(input))
                .setPositiveButton("儲存", null).setNegativeButton("取消", null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            String lower = value.toLowerCase(Locale.ROOT);
            if (!value.isEmpty() && !(lower.startsWith("https://") || lower.startsWith("tls://")
                    || lower.startsWith("quic://") || lower.startsWith("h3://") || lower.startsWith("udp://"))) {
                input.setError("只接受 https:// tls:// quic:// h3:// udp://");
                return;
            }
            savePolicy(new Policy(policy.mode, policy.directSsids, policy.directDomains,
                    policy.proxyDomains, policy.blockedDomains, value));
            AppLogger.log(this, "INFO", value.isEmpty() ? "私人 DNS 已停用" : "私人 DNS 已更新");
            d.dismiss();
            showSettings();
        }));
        d.show();
    }

    private void adblockActions() {
        ArrayList<String> items = new ArrayList<>();
        items.add("從檔案匯入");
        items.add("從網址下載");
        if (!policy.blockedDomains.isEmpty()) items.add("清除清單");
        dialog().setTitle("廣告網域清單")
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == 0) openFile(REQUEST_ADBLOCK);
                    else if (which == 1) downloadAdblockDialog();
                    else dialog().setTitle("清除 " + policy.blockedDomains.size() + " 個廣告網域？")
                                .setPositiveButton("清除", (x, w) -> { savePolicy(withBlocked(new LinkedHashSet<>())); showSettings(); })
                                .setNegativeButton("取消", null).show();
                })
                .setNegativeButton("取消", null).show();
    }

    private void downloadAdblockDialog() {
        EditText input = field("https://…", true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        dialog().setTitle("下載廣告網域清單").setView(padded(input))
                .setPositiveButton("下載", (d, w) -> {
                    String url = input.getText().toString();
                    toast("正在下載清單…");
                    new Thread(() -> {
                        try {
                            LinkedHashSet<String> blocked = new LinkedHashSet<>(
                                    AdBlockPlugin.parse(new java.io.StringReader(RuleSetStore.download(url))));
                            runOnUiThread(() -> {
                                savePolicy(withBlocked(blocked));
                                AppLogger.log(this, "INFO", "下載線上廣告清單，項目數=" + blocked.size());
                                toast("已套用 " + blocked.size() + " 個廣告網域");
                                if (screen == Screen.SETTINGS) showSettings();
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> toast("下載失敗：" + e.getMessage()));
                        }
                    }, "adblock-download").start();
                })
                .setNegativeButton("取消", null).show();
    }

    private void importAdBlock(Uri uri) {
        try (InputStreamReader reader = new InputStreamReader(getContentResolver().openInputStream(uri), StandardCharsets.UTF_8)) {
            LinkedHashSet<String> blocked = new LinkedHashSet<>(AdBlockPlugin.parse(reader));
            savePolicy(withBlocked(blocked));
            AppLogger.log(this, "INFO", "匯入廣告過濾清單，項目數=" + blocked.size());
            toast("已套用 " + blocked.size() + " 個廣告網域");
            showSettings();
        } catch (Exception e) {
            toast("匯入失敗：" + e.getMessage());
        }
    }

    private void exportProfiles() {
        if (ProxyProfileStore.load(this).isEmpty()) { toast("目前沒有可導出的節點"); return; }
        dialog().setTitle("導出明文節點？")
                .setMessage("導出的檔案包含節點密碼與金鑰，任何拿到檔案的人都能使用。請存到只有你能存取的位置。")
                .setPositiveButton("選擇位置", (d, w) -> {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_TITLE, "chris-proxy-nodes.txt");
                    startActivityForResult(intent, REQUEST_EXPORT);
                })
                .setNegativeButton("取消", null).show();
    }

    private void writeExport(Uri uri) {
        try (OutputStreamWriter writer = new OutputStreamWriter(getContentResolver().openOutputStream(uri), StandardCharsets.UTF_8)) {
            writer.write(ProxyProfileStore.exportPlaintext(this));
            AppLogger.log(this, "INFO", "使用者完成節點導出；節點內容未寫入 log");
            toast("節點已導出");
        } catch (Exception e) {
            AppLogger.log(this, "ERROR", "節點導出失敗，原因類型=" + e.getClass().getSimpleName());
            toast("導出失敗：" + e.getMessage());
        }
    }

    private void showLogs() {
        enter(Screen.LOGS, "運行記錄");
        TextView log = text(AppLogger.tail(this, 12000), 12, textColor());
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        page.addView(log);
        pageScroll.post(() -> pageScroll.fullScroll(View.FOCUS_DOWN));
    }

    // ------------------------------------------------------- config transfer

    private void importConfigMenu() {
        String[] items = {"掃描另一支手機的 QR", "從文件（.cpx 加密檔或 .json）", "貼上 JSON", "從瀏覽器／網址下載"};
        dialog().setTitle("匯入設定")
                .setItems(items, (d, which) -> {
                    if (which == 0) launchScanner("掃描舊手機上的轉移 QR");
                    else if (which == 1) openFile(REQUEST_CONFIG_FILE);
                    else if (which == 2) pasteConfigDialog();
                    else urlConfigDialog();
                })
                .setNegativeButton("取消", null).show();
    }

    private void exportConfigMenu() {
        String[] items = {"傳到另一支手機（掃碼＋OTP）", "加密設定檔（.cpx，搭配轉移碼）", "JSON（明文）"};
        dialog().setTitle("導出設定")
                .setItems(items, (d, which) -> {
                    if (which == 0) ExportGate.authenticate(this, "傳送設定到另一支手機", this::startLiveExport);
                    else if (which == 1) ExportGate.authenticate(this, "導出加密設定檔", this::startSealedExport);
                    else confirmJsonExport();
                })
                .setNegativeButton("取消", null).show();
    }

    /** Leaving a transfer screen ends any live session and drops secrets held in memory. */
    private void closeTransfer() {
        if (transferServer != null) { transferServer.close(); transferServer = null; }
        if (transferClient != null) { transferClient.close(); transferClient = null; }
        activeImportQr = null;
        pendingSealed = null;
        pendingSealedCode = null;
        transferOtp = null;
        transferQr = null;
    }

    // Exporter: QR → request (name + check code) → approve → OTP → done.

    private void startLiveExport() {
        java.net.InetAddress address = lanAddress();
        if (address == null) {
            dialog().setTitle("需要 Wi-Fi")
                    .setMessage("兩支手機要連在同一個 Wi-Fi 才能掃碼傳送。也可以改用加密設定檔。")
                    .setPositiveButton("改用加密設定檔", (d, w) -> startSealedExport())
                    .setNegativeButton("取消", null).show();
            return;
        }
        closeTransfer();
        transferServer = new TransferServer(() -> TransferBundle.build(this), new TransferServer.Listener() {
            @Override public void onRequest(String name, String check) {
                AppLogger.log(MainActivity.this, "INFO", "收到轉移請求");
                runOnUiThread(() -> { transferPeer = name; transferCheck = check; exportStep = ExportStep.REQUEST; renderExport(); });
            }
            @Override public void onApproved(String otp) {
                AppLogger.log(MainActivity.this, "INFO", "已核准轉移並產生 OTP");
                runOnUiThread(() -> { transferOtp = otp; transferMessage = null; exportStep = ExportStep.APPROVED; renderExport(); });
            }
            @Override public void onOtpWrong(int left) {
                runOnUiThread(() -> { transferMessage = "新手機輸入錯誤，還剩 " + left + " 次"; renderExport(); });
            }
            @Override public void onDone() {
                AppLogger.log(MainActivity.this, "INFO", "設定已傳送到另一支手機");
                runOnUiThread(() -> { transferOtp = null; exportStep = ExportStep.DONE; renderExport(); });
            }
            @Override public void onFailed(String reason) {
                runOnUiThread(() -> { transferOtp = null; transferMessage = reason; exportStep = ExportStep.FAILED; renderExport(); });
            }
        });
        transferAddress = address.getHostAddress();
        AppLogger.log(this, "INFO", "掃碼傳送開始，位址=" + transferAddress);
        transferServer.setDeviceName(deviceName());
        try {
            transferQr = transferServer.start(address);
        } catch (Exception e) {
            toast("無法開始傳送：" + e.getClass().getSimpleName());
            return;
        }
        exportStep = ExportStep.WAITING;
        transferMessage = null;
        enter(Screen.TRANSFER_EXPORT, "傳到另一支手機");
        renderExport();
    }

    private void renderExport() {
        if (screen != Screen.TRANSFER_EXPORT) return;
        page.removeAllViews();
        switch (exportStep) {
            case WAITING:
                muted("在新手機打開 Chris Proxy → 設定 → 匯入設定 → 掃描另一支手機的 QR。兩支手機要在同一個 Wi-Fi，10 分鐘內有效。QR 裡沒有任何設定內容。");
                if (transferQr != null) page.addView(qrView(transferQr));
                if (transferAddress != null) muted("這支手機的區網位址：" + transferAddress);
                page.addView(card(0, "取消", null, CardStyle.PLAIN, this::showSettings));
                break;
            case REQUEST: {
                page.addView(card(0, "「" + transferPeer + "」要求取得設定", "請確認新手機顯示的核對碼和下面相同",
                        CardStyle.WARNING, () -> {}));
                page.addView(bigCode("核對碼", transferCheck));
                page.addView(card(0, "核准並顯示 OTP", "核對碼一致才核准", CardStyle.PRIMARY, () -> {
                    if (transferServer != null) transferServer.approve();
                }));
                page.addView(card(0, "拒絕", "核對碼不同或不認得這支手機", CardStyle.DANGER, () -> {
                    if (transferServer != null) transferServer.reject();
                }));
                break;
            }
            case APPROVED:
                muted("在新手機輸入這組 OTP。5 分鐘內有效、只能使用一次，錯 5 次會取消。");
                page.addView(bigCode("OTP", transferOtp));
                if (transferMessage != null) muted(transferMessage);
                page.addView(card(0, "取消傳送", null, CardStyle.DANGER, this::showSettings));
                break;
            case DONE:
                page.addView(card(0, "轉移完成", "設定已加密傳送到「" + transferPeer + "」", CardStyle.TONAL, this::showSettings));
                break;
            default:
                page.addView(card(0, "轉移未完成", transferMessage, CardStyle.WARNING, this::showSettings));
        }
    }

    // Importer: scan → check code → wait for approval → OTP → preview.

    private String activeImportQr;

    private void startLiveImport(String qr) {
        if (transferClient != null && qr.equals(activeImportQr)) {
            AppLogger.log(this, "INFO", "忽略重複的轉移 QR");
            return;
        }
        TransferClient.Target target;
        try {
            target = TransferClient.parse(qr);
        } catch (Exception e) {
            toast("無法辨識轉移 QR");
            return;
        }
        closeTransfer();
        transferClient = new TransferClient(new TransferClient.Listener() {
            @Override public void onCheckCode(String check, String from) {
                runOnUiThread(() -> { transferCheck = check; transferPeer = from; importStep = ImportStep.CHECK; renderImport(); });
            }
            @Override public void onApproved() {
                runOnUiThread(() -> { transferMessage = null; importStep = ImportStep.OTP; renderImport(); });
            }
            @Override public void onOtpWrong(int left) {
                runOnUiThread(() -> { transferMessage = "OTP 不正確，還剩 " + left + " 次"; renderImport(); });
            }
            @Override public void onBundle(String json) {
                runOnUiThread(() -> showTransferPreview(json, "另一支手機"));
            }
            @Override public void onFailed(String reason) {
                runOnUiThread(() -> { transferMessage = reason; importStep = ImportStep.FAILED; renderImport(); });
            }
        });
        importStep = ImportStep.CONNECTING;
        transferMessage = null;
        transferPeer = null;
        TransferClient client = transferClient;
        enter(Screen.TRANSFER_IMPORT, "從另一支手機匯入");
        activeImportQr = qr;
        AppLogger.log(this, "INFO", "掃碼匯入：連線 " + target.host);
        client.connect(target, deviceName());
        renderImport();
    }

    private void renderImport() {
        if (screen != Screen.TRANSFER_IMPORT || importStep == null) return;
        page.removeAllViews();
        switch (importStep) {
            case CONNECTING:
                muted("正在連線舊手機…");
                break;
            case CHECK:
                if (transferPeer != null && !transferPeer.isEmpty()) {
                    page.addView(card(0, "來自「" + transferPeer + "」", "確認是你要轉移的那支手機", CardStyle.TONAL, () -> {}));
                }
                muted("確認舊手機顯示相同的核對碼，並等待舊手機按下核准。");
                page.addView(bigCode("核對碼", transferCheck));
                break;
            case OTP: {
                muted("輸入舊手機上顯示的 6 位數 OTP。");
                EditText otp = field("OTP", true);
                otp.setInputType(InputType.TYPE_CLASS_NUMBER);
                otp.setTextSize(24);
                otp.setGravity(Gravity.CENTER);
                page.addView(otp);
                if (transferMessage != null) muted(transferMessage);
                page.addView(card(0, "確認", null, CardStyle.PRIMARY, () -> {
                    if (transferClient != null) transferClient.submitOtp(otp.getText().toString());
                }));
                otp.requestFocus();
                break;
            }
            default:
                page.addView(card(0, "匯入未完成", transferMessage, CardStyle.WARNING, this::showSettings));
        }
        if (importStep != ImportStep.FAILED) page.addView(card(0, "取消", null, CardStyle.PLAIN, this::showSettings));
    }

    // Sealed file: encrypted with a one-time transfer code shown on this screen.

    private void startSealedExport() {
        String code = TransferCodec.newCode();
        toast("正在加密…");
        new Thread(() -> {
            try {
                byte[] sealed = TransferCodec.seal(TransferBundle.build(this), code);
                runOnUiThread(() -> showSealedExport(sealed, code));
            } catch (Exception e) {
                runOnUiThread(() -> toast("加密失敗：" + e.getClass().getSimpleName()));
            }
        }, "transfer-seal").start();
    }

    private void showSealedExport(byte[] sealed, String code) {
        enter(Screen.TRANSFER_EXPORT, "加密設定檔");
        pendingSealed = sealed;
        pendingSealedCode = code;
        muted("把檔案傳給新手機，再把轉移碼另外告訴對方（例如當面念出）。沒有轉移碼，檔案無法打開。");
        page.addView(bigCode("轉移碼", TransferCodec.format(code)));
        page.addView(card(0, "分享檔案", "透過聊天、郵件、雲端硬碟等傳送", CardStyle.PRIMARY,
                () -> shareFile(pendingSealed, sealedFileName(), "application/octet-stream")));
        page.addView(card(0, "存成檔案", null, CardStyle.TONAL, () -> createDocument(REQUEST_SAVE_SEALED,
                "application/octet-stream", sealedFileName())));
        page.addView(card(0, "完成", "離開後轉移碼不再顯示", CardStyle.PLAIN, this::showSettings));
    }

    private static String sealedFileName() {
        return "chris-proxy-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new java.util.Date()) + ".cpx";
    }

    private void confirmJsonExport() {
        dialog().setTitle("導出明文 JSON？")
                .setMessage("JSON 會以明文包含所有節點密碼與金鑰，任何拿到檔案的人都能使用。建議改用加密設定檔或掃碼傳送。")
                .setPositiveButton("仍要導出", (d, w) -> ExportGate.authenticate(this, "導出 JSON 設定檔", () -> {
                    String[] items = {"存成檔案", "分享"};
                    dialog().setTitle("JSON 設定檔")
                            .setItems(items, (x, which) -> {
                                String name = "chris-proxy-config.json";
                                if (which == 0) createDocument(REQUEST_SAVE_JSON, "application/json", name);
                                else shareFile(exportJsonBytes(), name, "application/json");
                            })
                            .setNegativeButton("取消", null).show();
                }))
                .setNegativeButton("取消", null).show();
    }

    private byte[] exportJsonBytes() {
        try {
            return new org.json.JSONObject(TransferBundle.build(this)).toString(2).getBytes(StandardCharsets.UTF_8);
        } catch (org.json.JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // Import sources.

    private void pasteConfigDialog() {
        EditText input = field("貼上 Chris Proxy 的 JSON 設定", false);
        input.setMinLines(6);
        dialog().setTitle("貼上 JSON").setView(padded(input))
                .setPositiveButton("下一步", (d, w) -> importConfigBytes(
                        input.getText().toString().getBytes(StandardCharsets.UTF_8), "貼上的 JSON"))
                .setNegativeButton("取消", null).show();
    }

    private void urlConfigDialog() {
        EditText input = field("https://…/chris-proxy.cpx 或 .json", true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        dialog().setTitle("從網址下載設定")
                .setMessage("加密設定檔下載後仍需要轉移碼。")
                .setView(padded(input))
                .setPositiveButton("下載", (d, w) -> importConfigUrl(input.getText().toString().trim()))
                .setNegativeButton("取消", null).show();
    }

    private void importConfigUrl(String url) {
        toast("正在下載設定…");
        new Thread(() -> {
            try {
                byte[] data = RuleSetStore.downloadBytes(url);
                runOnUiThread(() -> importConfigBytes(data, "網址"));
            } catch (Exception e) {
                runOnUiThread(() -> toast("下載失敗：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
            }
        }, "config-download").start();
    }

    private static boolean looksLikeConfig(byte[] data) {
        if (TransferCodec.isSealed(data)) return true;
        String head = new String(data, 0, Math.min(data.length, 200), StandardCharsets.UTF_8).trim();
        return head.startsWith("{") && head.contains(TransferBundle.FORMAT);
    }

    private void importConfigBytes(byte[] data, String source) {
        if (TransferCodec.isSealed(data)) {
            askTransferCode(data, source, null);
            return;
        }
        String text = new String(data, StandardCharsets.UTF_8).trim();
        if (TransferClient.isTransferQr(text)) { startLiveImport(text); return; }
        showTransferPreview(text, source);
    }

    private void askTransferCode(byte[] sealed, String source, String error) {
        EditText input = field("XXXXX-XXXXX", true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (error != null) input.setError(error);
        dialog().setTitle("輸入轉移碼")
                .setMessage("導出這份設定的手機上顯示的 10 碼轉移碼。")
                .setView(padded(input))
                .setPositiveButton("解開", (d, w) -> {
                    String code = input.getText().toString();
                    toast("正在解密…");
                    new Thread(() -> {
                        try {
                            String json = TransferCodec.open(sealed, code);
                            runOnUiThread(() -> showTransferPreview(json, source));
                        } catch (TransferCodec.WrongCodeException e) {
                            runOnUiThread(() -> askTransferCode(sealed, source, "轉移碼不正確"));
                        } catch (Exception e) {
                            runOnUiThread(() -> toast("無法打開設定檔：" + e.getClass().getSimpleName()));
                        }
                    }, "transfer-open").start();
                })
                .setNegativeButton("取消", null).show();
    }

    private void showTransferPreview(String json, String source) {
        org.json.JSONObject root;
        TransferBundle.Summary summary;
        try {
            root = TransferBundle.parse(json);
            summary = TransferBundle.summarize(root);
        } catch (Exception e) {
            toast("不是 Chris Proxy 設定檔");
            return;
        }
        if (transferClient != null) { transferClient.close(); transferClient = null; }
        enter(Screen.TRANSFER_IMPORT, "匯入設定");
        importStep = null;
        LinearLayout block = surfaceBlock();
        TextView head = text(summary.nodes + " 個節點 · " + summary.rules + " 條規則", 22, textColor());
        head.setTypeface(null, Typeface.BOLD);
        block.addView(head);
        block.addView(text(summary.ruleSets + " 個規則集 · " + summary.ssids + " 個 Wi-Fi 例外 · 廣告網域 "
                + summary.blocked + (summary.privateDns ? " · 含私人 DNS" : "") + " · " + summary.mode, 14, mutedColor()));
        block.addView(text("來源：" + source, 12, mutedColor()));
        page.addView(block);
        page.addView(card(0, "取代這支手機的全部設定", "和來源完全相同；目前的節點與規則會被移除", CardStyle.PRIMARY, () ->
                dialog().setTitle("取代全部設定？")
                        .setMessage("這支手機目前的節點、規則、規則集與設定都會被取代，無法復原。")
                        .setPositiveButton("取代", (d, w) -> applyTransfer(root, true))
                        .setNegativeButton("取消", null).show()));
        page.addView(card(0, "合併到現有設定", "只加入這裡沒有的節點、規則與規則集；保留目前的模式與 DNS",
                CardStyle.TONAL, () -> applyTransfer(root, false)));
        page.addView(card(0, "取消", null, CardStyle.PLAIN, this::showSettings));
    }

    private void applyTransfer(org.json.JSONObject root, boolean replace) {
        try {
            TransferBundle.apply(this, root, replace);
        } catch (Exception e) {
            AppLogger.log(this, "ERROR", "設定匯入失敗=" + e.getClass().getSimpleName());
            toast("匯入失敗：" + e.getMessage());
            return;
        }
        policy = PolicyStore.load(this);
        routing = RoutingStore.load(this);
        applyChange();
        refreshStaleRuleSets();
        toast(replace ? "已取代為匯入的設定" : "已合併匯入的設定");
        if (replace) recreate(); else showHome();
    }

    // Helpers.

    private View qrView(String content) {
        ImageView image = new ImageView(this);
        try {
            int size = dp(280);
            android.graphics.Bitmap bitmap = new com.journeyapps.barcodescanner.BarcodeEncoder()
                    .encodeBitmap(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size);
            image.setImageBitmap(bitmap);
        } catch (Exception e) {
            toast("無法產生 QR");
        }
        // White quiet zone so the code scans in dark mode too.
        image.setBackgroundColor(Color.WHITE);
        image.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(312), dp(312));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.setMargins(0, dp(8), 0, dp(12));
        image.setLayoutParams(lp);
        image.setContentDescription("轉移 QR");
        return image;
    }

    private View bigCode(String label, String value) {
        LinearLayout block = surfaceBlock();
        block.setGravity(Gravity.CENTER_HORIZONTAL);
        block.addView(text(label, 13, mutedColor()));
        TextView code = text(value == null ? "" : value, 34, textColor());
        code.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        code.setLetterSpacing(0.12f);
        code.setTextIsSelectable(false);
        block.addView(code);
        return block;
    }

    /** The user's own name for this phone; falls back to the system device name. */
    private String deviceName() {
        String custom = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_DEVICE_NAME, "");
        if (custom != null && !custom.trim().isEmpty()) return custom.trim();
        String name = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
        return name == null || name.trim().isEmpty() ? Build.MODEL : name.trim();
    }

    private void editDeviceName() {
        EditText input = field(deviceName(), true);
        input.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_DEVICE_NAME, ""));
        dialog().setTitle("這支手機的名稱")
                .setMessage("顯示在首頁問候，以及掃碼轉移時給另一支手機辨認。留空使用系統裝置名稱。")
                .setView(padded(input))
                .setPositiveButton("儲存", (d, w) -> {
                    String value = input.getText().toString().trim();
                    if (value.length() > 30) value = value.substring(0, 30);
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_DEVICE_NAME, value).apply();
                    showSettings();
                })
                .setNegativeButton("取消", null).show();
    }

    /**
     * This phone's private IPv4 address on Wi-Fi/Ethernet for the transfer server.
     * ConnectivityManager first; if it reports nothing (seen while a VPN is up),
     * fall back to the interfaces themselves, preferring wlan*.
     */
    private java.net.InetAddress lanAddress() {
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        if (cm != null) {
            for (android.net.Network network : cm.getAllNetworks()) {
                android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                android.net.LinkProperties link = cm.getLinkProperties(network);
                if (caps == null || link == null || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue;
                if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                        && !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) continue;
                for (android.net.LinkAddress address : link.getLinkAddresses()) {
                    java.net.InetAddress ip = address.getAddress();
                    if (ip instanceof java.net.Inet4Address && ip.isSiteLocalAddress()) return ip;
                }
            }
        }
        java.net.InetAddress fallback = null;
        try {
            for (java.net.NetworkInterface nic : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isPointToPoint() || nic.getName().startsWith("tun")) continue;
                for (java.net.InetAddress ip : java.util.Collections.list(nic.getInetAddresses())) {
                    if (!(ip instanceof java.net.Inet4Address) || !ip.isSiteLocalAddress()) continue;
                    if (nic.getName().startsWith("wlan") || nic.getName().startsWith("eth")) return ip;
                    if (fallback == null) fallback = ip;
                }
            }
        } catch (java.net.SocketException e) {
            AppLogger.log(this, "WARN", "列舉網路介面失敗");
        }
        if (fallback == null) AppLogger.log(this, "WARN", "找不到區網 IPv4，無法開始掃碼傳送");
        return fallback;
    }

    private void createDocument(int requestCode, String mime, String name) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime);
        intent.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(intent, requestCode);
    }

    private void writeBytes(Uri uri, byte[] data, String done) {
        if (data == null) { toast("沒有可儲存的內容"); return; }
        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new java.io.IOException("無法寫入");
            out.write(data);
            AppLogger.log(this, "INFO", done);
            toast(done);
        } catch (Exception e) {
            toast("儲存失敗：" + e.getMessage());
        }
    }

    /** Shares through a short-lived cache file and FileProvider; nothing is left in shared storage. */
    private void shareFile(byte[] data, String name, String mime) {
        if (data == null) { toast("沒有可分享的內容"); return; }
        try {
            java.io.File dir = new java.io.File(getCacheDir(), "share");
            if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("無法建立暫存資料夾");
            java.io.File[] old = dir.listFiles();
            if (old != null) for (java.io.File f : old) //noinspection ResultOfMethodCallIgnored
                f.delete();
            java.io.File file = new java.io.File(dir, name);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) { out.write(data); }
            Uri uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".files", file);
            Intent send = new Intent(Intent.ACTION_SEND).setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "分享設定檔"));
        } catch (Exception e) {
            toast("分享失敗：" + e.getMessage());
        }
    }

    private void shareLogs() {
        String core = "";
        try {
            java.io.File file = new java.io.File(getFilesDir(), "logs/core.log");
            if (file.exists()) {
                String all = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                core = all.length() > 60000 ? all.substring(all.length() - 60000) : all;
            }
        } catch (Exception ignored) {
            // App log alone is still useful.
        }
        String text = "Chris Proxy " + versionName() + "\n\n== App ==\n" + AppLogger.tail(this, 30000)
                + "\n\n== Core ==\n" + core;
        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text), "分享運行記錄"));
    }

    // ------------------------------------------------------------ VPN control

    private void prepareVpn() {
        if (ProxyProfileStore.load(this).isEmpty()) { showNodes(); return; }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_PERMISSIONS);
        }
        Intent intent = VpnService.prepare(this);
        if (intent != null) startActivityForResult(intent, REQUEST_VPN); else startProxyService();
    }

    private void startProxyService() {
        Intent service = new Intent(this, ProxyVpnService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        AppLogger.log(this, "INFO", "已提交 VPN 前景服務啟動請求");
        handler.postDelayed(this::showHome, 150);
        handler.postDelayed(() -> measureLatency(true), 3000);
    }

    private void stopProxy() {
        ProxyVpnService.stop(this);
        AppLogger.log(this, "INFO", "使用者停止服務");
        handler.postDelayed(() -> { measureLatency(); showHome(); }, 150);
    }

    /** Pushes the saved state into a running VPN without disconnecting. */
    private void applyChange() {
        ProxyVpnService.reloadIfRunning(this);
    }

    private void saveRouting(Routing next) {
        saveRouting(next, "設定變更");
    }

    private void saveRouting(Routing next, String reason) {
        routing = next;
        RoutingStore.save(this, next, reason);
        applyChange();
    }

    private void savePolicy(Policy next) {
        policy = next;
        PolicyStore.save(this, next);
        applyChange();
    }

    private boolean hasWifiPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestWifiPermissions() {
        LinkedHashSet<String> permissions = new LinkedHashSet<>();
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        requestPermissions(permissions.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (screen == Screen.SSIDS) showSsids();
        else if (screen == Screen.HOME) showHome();
    }

    private void openFile(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/*", "application/octet-stream", "application/x-yaml"});
        startActivityForResult(intent, requestCode);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN) {
            if (resultCode == RESULT_OK) startProxyService();
            else {
                AppLogger.log(this, "WARN", "使用者未授予系統 VPN 權限");
                toast("需要允許建立 VPN 才能連線");
            }
            return;
        }
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQUEST_QR) {
            String value = data.getStringExtra("SCAN_RESULT");
            if (value == null || value.trim().isEmpty()) toast("掃描結果是空白"); else addProfiles(value);
            return;
        }
        Uri uri = data.getData();
        if (uri == null) return;
        if (requestCode == REQUEST_RULE_FILE) importLoon(uri);
        else if (requestCode == REQUEST_ADBLOCK) importAdBlock(uri);
        else if (requestCode == REQUEST_LIST_FILE) importFile(uri, "檔案");
        else if (requestCode == REQUEST_EXPORT) writeExport(uri);
        else if (requestCode == REQUEST_CONFIG_FILE) importFile(uri, "文件");
        else if (requestCode == REQUEST_SAVE_SEALED) writeBytes(uri, pendingSealed, "加密設定檔已儲存");
        else if (requestCode == REQUEST_SAVE_JSON) writeBytes(uri, exportJsonBytes(), "JSON 設定檔已儲存");
    }

    // ------------------------------------------------------------- widgets

    private void muted(String value) {
        TextView view = text(value, 14, mutedColor());
        view.setLineSpacing(0, 1.2f);
        view.setPadding(dp(2), 0, 0, dp(10));
        page.addView(view);
    }

    private LinearLayout surfaceBlock() {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(18), dp(16), dp(18), dp(14));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(18));
        shape.setColor(surfaceColor());
        shape.setStroke(dp(1), lineColor());
        block.setBackground(shape);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(8));
        block.setLayoutParams(lp);
        return block;
    }

    /** ERP-style three-line row: title, key facts, then secondary detail; tap opens details. */
    private void listRow(String first, String second, String third, int firstColor, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(12), dp(10));
        row.setMinimumHeight(dp(64));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(14));
        shape.setColor(surfaceColor());
        shape.setStroke(dp(1), lineColor());
        row.setBackground(new RippleDrawable(ColorStateList.valueOf((textColor() & 0x00FFFFFF) | 0x22000000), shape, null));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView a = text(first, 15, firstColor);
        a.setTypeface(null, Typeface.BOLD);
        a.setSingleLine(true);
        a.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(a);
        TextView b = text(second, 13, textColor());
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(b);
        if (third != null && !third.isEmpty()) {
            TextView c = text(third, 12, mutedColor());
            c.setSingleLine(true);
            c.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(c);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        TextView chevron = text("›", 24, mutedColor());
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(chevron);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> action.run());
        row.setContentDescription(first + "，" + second);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(3), 0, dp(3));
        page.addView(row, lp);
    }

    private View card(int icon, String heading, String subtitle, CardStyle style, Runnable action) {
        int bg;
        int fg;
        int sub;
        switch (style) {
            case PRIMARY: bg = accentColor(); fg = sub = onAccentColor(); break;
            case TONAL: bg = raisedColor(); fg = sub = onRaisedColor(); break;
            case WARNING: bg = warningSurface(); fg = sub = warningText(); break;
            case DANGER: bg = surfaceColor(); fg = sub = dangerColor(); break;
            default: bg = surfaceColor(); fg = textColor(); sub = mutedColor();
        }
        boolean big = style == CardStyle.PRIMARY || style == CardStyle.TONAL || style == CardStyle.WARNING;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(big ? 16 : 14), dp(14), dp(big ? 16 : 14));
        row.setMinimumHeight(dp(big ? 64 : 56));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(16));
        shape.setColor(bg);
        if (style == CardStyle.PLAIN || style == CardStyle.DANGER) shape.setStroke(dp(1), lineColor());
        row.setBackground(new RippleDrawable(ColorStateList.valueOf((fg & 0x00FFFFFF) | 0x33000000), shape, null));
        if (icon != 0) {
            ImageView image = new ImageView(this);
            image.setImageResource(icon);
            image.setImageTintList(ColorStateList.valueOf(fg));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(22), dp(22));
            lp.setMargins(0, 0, dp(14), 0);
            row.addView(image, lp);
        }
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(heading, big ? 17 : 16, fg);
        if (big) t.setTypeface(null, Typeface.BOLD);
        texts.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = text(subtitle, 13, sub);
            s.setAlpha(big ? 0.85f : 1f);
            s.setPadding(0, dp(2), 0, 0);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        TextView chevron = text("›", 24, fg);
        chevron.setAlpha(0.6f);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(chevron);
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(subtitle == null ? heading : heading + "，" + subtitle);
        row.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(5), 0, dp(5));
        row.setLayoutParams(lp);
        return row;
    }

    private Button pillButton(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(primary ? onAccentColor() : onRaisedColor());
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(24));
        shape.setColor(primary ? accentColor() : raisedColor());
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000), shape, null));
        button.setMinHeight(dp(48));
        return button;
    }

    private BottomSheetDialog sheet() {
        return new BottomSheetDialog(this);
    }

    private LinearLayout sheetBody(BottomSheetDialog sheet, String heading, String subtitle) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(20), dp(20), dp(24));
        TextView h = text(heading, 19, textColor());
        h.setTypeface(null, Typeface.BOLD);
        body.addView(h);
        if (subtitle != null) {
            TextView s = text(subtitle, 13, mutedColor());
            s.setPadding(0, dp(4), 0, dp(12));
            s.setTextIsSelectable(true);
            body.addView(s);
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(surfaceColor());
        float r = dp(24);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(bg);
        scroll.setClipToOutline(true);
        scroll.addView(body);
        sheet.setContentView(scroll);
        sheet.setOnShowListener(ignored -> {
            View container = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (container != null) container.setBackgroundColor(Color.TRANSPARENT);
        });
        return body;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private EditText field(String hint, boolean singleLine) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextSize(15);
        input.setSingleLine(singleLine);
        if (!singleLine) input.setGravity(Gravity.TOP | Gravity.START);
        return input;
    }

    private Spinner spinner(String[] items) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items);
        spinner.setAdapter(adapter);
        spinner.setMinimumHeight(dp(48));
        return spinner;
    }

    private View padded(View child) {
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(dp(22), dp(8), dp(22), 0);
        frame.addView(child, new FrameLayout.LayoutParams(-1, -2));
        return frame;
    }

    private ImageButton iconButton(int icon, String label) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setContentDescription(label);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        androidx.appcompat.widget.TooltipCompat.setTooltipText(button, label);
        button.setColorFilter(textColor());
        android.util.TypedValue background = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true);
        button.setBackgroundResource(background.resourceId);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return button;
    }

    private void setDot(View dot, int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        dot.setBackground(shape);
    }

    /** Material dialog themed from AppTheme, so buttons use the blue accent in both modes. */
    private AlertDialog.Builder dialog() {
        return new com.google.android.material.dialog.MaterialAlertDialogBuilder(this);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void toast(String value) { Toast.makeText(this, value == null ? "操作失敗" : value, Toast.LENGTH_LONG).show(); }

    // ---------------------------------------------------------------- theme

    private String themeMode() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        return p.getString(KEY_THEME_MODE, "system");
    }

    private void applyThemePreference() {
        String mode = themeMode();
        AppCompatDelegate.setDefaultNightMode("light".equals(mode) ? AppCompatDelegate.MODE_NIGHT_NO
                : "dark".equals(mode) ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    private boolean uiDark() {
        String mode = themeMode();
        return "dark".equals(mode) || (!"light".equals(mode)
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
    }

    // Blue palette (kept from 0.3.0; deliberately not the ERP green).
    private int backgroundColor() { return uiDark() ? Color.rgb(16, 21, 28) : Color.rgb(246, 248, 251); }
    private int surfaceColor() { return uiDark() ? Color.rgb(27, 35, 45) : Color.WHITE; }
    private int raisedColor() { return uiDark() ? Color.rgb(30, 47, 77) : Color.rgb(225, 233, 251); }
    private int onRaisedColor() { return uiDark() ? Color.rgb(201, 218, 255) : Color.rgb(29, 63, 138); }
    private int accentColor() { return uiDark() ? Color.rgb(141, 180, 255) : Color.rgb(52, 103, 214); }
    private int onAccentColor() { return uiDark() ? Color.rgb(11, 29, 63) : Color.WHITE; }
    private int textColor() { return uiDark() ? Color.rgb(238, 243, 248) : Color.rgb(24, 33, 43); }
    private int mutedColor() { return uiDark() ? Color.rgb(168, 180, 194) : Color.rgb(91, 104, 120); }
    private int lineColor() { return uiDark() ? Color.rgb(52, 64, 77) : Color.rgb(221, 227, 234); }
    private int warningSurface() { return uiDark() ? Color.rgb(61, 42, 0) : Color.rgb(255, 239, 214); }
    private int warningText() { return uiDark() ? Color.rgb(255, 221, 176) : Color.rgb(92, 57, 0); }
    private int dangerColor() { return uiDark() ? Color.rgb(242, 184, 181) : Color.rgb(179, 38, 30); }
    private int amberColor() { return uiDark() ? Color.rgb(251, 191, 36) : Color.rgb(180, 83, 9); }
    private int successColor() { return uiDark() ? Color.rgb(123, 216, 162) : Color.rgb(27, 127, 75); }
}
