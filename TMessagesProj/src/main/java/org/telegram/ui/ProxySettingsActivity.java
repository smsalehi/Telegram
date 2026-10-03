/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui;

import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.Transition;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.XrayProxyManager;
import org.telegram.messenger.AetherProxyManager;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.GeminiTranslator;
import org.telegram.messenger.SvgHelper;
import org.telegram.messenger.Utilities;
import org.telegram.utils.proxy.WebProxyTransport;
import org.telegram.utils.proxy.ProxySettings;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.RadioCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.QRCodeBottomSheet;
import org.telegram.ui.Components.SectionsScrollView;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;

public class ProxySettingsActivity extends BaseFragment {

    private final static int FIELD_IP = 0;
    private final static int FIELD_PORT = 1;
    private final static int FIELD_USER = 2;
    private final static int FIELD_PASSWORD = 3;
    private final static int FIELD_SECRET = 4;
    private final static int FIELD_XRAY_CONFIG = 5;
    private final static int FIELD_AETHER_PROTOCOL = 6;
    private final static int FIELD_AETHER_SCAN = 7;
    private final static int FIELD_AETHER_IP = 8;
    private final static int FIELD_AETHER_TRANSPORT = 9;
    private final static int FIELD_AETHER_FRAGMENT = 10;
    private final static int FIELD_AETHER_NOIZE = 11;
    private final static int FIELD_AETHER_QUICK_RECONNECT = 12;
    private final static int FIELD_AETHER_PEERS = 13;
    private final static int FIELD_AETHER_NAME = 14;
    private final static int FIELD_COUNT = 15;

    private static final String[] AETHER_PROTOCOL_LABELS = {"MASQUE", "WireGuard", "gool", "mim"};
    private static final String[] AETHER_PROTOCOL_VALUES = {"masque", "wg", "gool", "mim"};
    private static final String[] AETHER_SCAN_LABELS = {"Turbo", "Balanced", "Thorough", "Verified", "Ironclad"};
    private static final String[] AETHER_SCAN_VALUES = {"turbo", "balanced", "thorough", "verified", "ironclad"};
    private static final String[] AETHER_IP_LABELS = {"IPv4", "IPv6", "Dual"};
    private static final String[] AETHER_IP_VALUES = {"v4", "v6", "dual"};
    private static final String[] AETHER_TRANSPORT_LABELS = {"Auto", "H2 (HTTP/2)", "H3 (HTTP/3)"};
    private static final String[] AETHER_TRANSPORT_VALUES = {"", "h2", "h3"};
    private static final String[] AETHER_FRAGMENT_LABELS = {"Off", "On"};
    private static final String[] AETHER_FRAGMENT_VALUES = {"", "1"};
    private static final String[] AETHER_NOIZE_LABELS = {"Off", "Light", "Firewall", "Balanced", "GFW", "Aggressive"};
    private static final String[] AETHER_NOIZE_VALUES = {"off", "light", "firewall", "balanced", "gfw", "aggressive"};
    private static final String[] AETHER_BOOL_LABELS = {"On", "Off"};
    private static final String[] AETHER_BOOL_VALUES = {"1", ""};

    private EditTextBoldCursor[] inputFields;
    private ScrollView scrollView;
    private LinearLayout linearLayout2;
    private LinearLayout inputFieldsContainer;
    private HeaderCell headerCell;
    private ShadowSectionCell[] sectionCell = new ShadowSectionCell[3];
    private TextInfoPrivacyCell[] bottomCells = new TextInfoPrivacyCell[3];
    private TextSettingsCell shareCell;
    private TextSettingsCell pasteCell;
    private TextSettingsCell importConfigCell;
    private TextSettingsCell xrayLogCell;
    private TextSettingsCell redirectImportCell;
    private TextSettingsCell redownloadCell;
    private TextSettingsCell xrayStatusCell;
    private TextSettingsCell aetherStatusCell;
    private ActionBarMenuItem doneItem;
    private boolean xrayStatusUpdates;
    private final Runnable xrayStatusUpdater = new Runnable() {
        @Override
        public void run() {
            if (!xrayStatusUpdates) {
                return;
            }
            updateXrayStatusCell();
            updateAetherStatusCell();
            AndroidUtilities.runOnUIThread(this, 500);
        }
    };
    private RadioCell[] typeCell = new RadioCell[6];
    private ProxySettings.Type currentType;

    private ProxySettings pasteProxySettings;
    private SharedConfig.ProxyInfo pasteProxyInfo;
    private String pasteString;

    private float shareDoneProgress = 1f;
    private float[] shareDoneProgressAnimValues = new float[2];
    private boolean shareDoneEnabled = true;
    private ValueAnimator shareDoneAnimator;

    private ClipboardManager clipboardManager;

    private boolean addingNewProxy;

    private SharedConfig.ProxyInfo currentProxyInfo;

    private boolean ignoreOnTextChange;

    private static final int done_button = 1;
    private static final int IMPORT_CONFIG_REQUEST = 17501;
    private static final int IMPORT_IPS_REQUEST = 17502;

    public static class TypeCell extends FrameLayout {

        private TextView textView;
        private ImageView checkImage;
        private boolean needDivider;

        public TypeCell(Context context) {
            super(context);

            setWillNotDraw(false);

            textView = new TextView(context);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            textView.setLines(1);
            textView.setMaxLines(1);
            textView.setSingleLine(true);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            textView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, LocaleController.isRTL ? 23 + 48 : 21, 0, LocaleController.isRTL ? 21 : 23, 0));

            checkImage = new ImageView(context);
            checkImage.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_featuredStickers_addedIcon), PorterDuff.Mode.MULTIPLY));
            checkImage.setImageResource(R.drawable.sticker_added);
            addView(checkImage, LayoutHelper.createFrame(19, 14, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL, 21, 0, 21, 0));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(50) + (needDivider ? 1 : 0), MeasureSpec.EXACTLY));
        }

        public void setValue(String name, boolean checked, boolean divider) {
            textView.setText(name);
            checkImage.setVisibility(checked ? VISIBLE : INVISIBLE);
            needDivider = divider;
        }

        public void setTypeChecked(boolean value) {
            checkImage.setVisibility(value ? VISIBLE : INVISIBLE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (needDivider) {
                canvas.drawLine(LocaleController.isRTL ? 0 : AndroidUtilities.dp(20), getMeasuredHeight() - 1, getMeasuredWidth() - (LocaleController.isRTL ? AndroidUtilities.dp(20) : 0), getMeasuredHeight() - 1, Theme.dividerPaint);
            }
        }
    }

    public ProxySettingsActivity() {
        super();
        currentProxyInfo = new SharedConfig.ProxyInfo(ProxySettings.EMPTY);
        addingNewProxy = true;
    }

    public ProxySettingsActivity(SharedConfig.ProxyInfo proxyInfo) {
        super();
        currentProxyInfo = proxyInfo;
    }

    private ClipboardManager.OnPrimaryClipChangedListener clipChangedListener = this::updatePasteCell;

    @Override
    public void onResume() {
        super.onResume();
        AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
        clipboardManager.addPrimaryClipChangedListener(clipChangedListener);
        updatePasteCell();
        xrayStatusUpdates = true;
        AndroidUtilities.runOnUIThread(xrayStatusUpdater);
    }

    @Override
    public void onPause() {
        super.onPause();
        clipboardManager.removePrimaryClipChangedListener(clipChangedListener);
        xrayStatusUpdates = false;
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle(LocaleController.getString(R.string.ProxyDetails));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        if (parentLayout != null && parentLayout.isLayersLayout()) {
            actionBar.setOccupyStatusBar(false);
        }

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == done_button) {
                    if (getParentActivity() == null) {
                        return;
                    }

                    currentProxyInfo.settings = ProxySettings.builder()
                        .setType(currentType)
                        .setAddress(currentType == ProxySettings.Type.AETHER ? "aether" : currentType == ProxySettings.Type.XRAY ? "xray" : inputFields[FIELD_IP].getText().toString())
                        .setPort(currentType == ProxySettings.Type.WEB ? 0 : currentType == ProxySettings.Type.AETHER ? AetherProxyManager.getLocalSocksPort() : currentType == ProxySettings.Type.XRAY ? 0 : Utilities.parseInt(inputFields[FIELD_PORT].getText()))
                        .setUser(currentType == ProxySettings.Type.SOCKS5 ? inputFields[FIELD_USER].getText().toString() : "")
                        .setPassword(currentType == ProxySettings.Type.SOCKS5 ? inputFields[FIELD_PASSWORD].getText().toString() : "")
                        .setSecret(currentType != ProxySettings.Type.SOCKS5 && currentType != ProxySettings.Type.XRAY && currentType != ProxySettings.Type.AETHER && currentType != ProxySettings.Type.REDIRECT_IP ? inputFields[FIELD_SECRET].getText().toString() : "")
                        .build();
                    if (currentType == ProxySettings.Type.XRAY) {
                        currentProxyInfo.xrayConfig = inputFields[FIELD_XRAY_CONFIG].getText().toString();
                        currentProxyInfo.normalizeXrayFields();
                        try {
                            String remarks = new org.json.JSONObject(currentProxyInfo.xrayConfig).optString("remarks", "");
                            if (!TextUtils.isEmpty(remarks)) {
                                currentProxyInfo.proxyName = remarks;
                            }
                        } catch (Exception ignored) {
                        }
                    } else if (currentType == ProxySettings.Type.AETHER) {
                        currentProxyInfo.aetherProtocol = aetherFieldValue(FIELD_AETHER_PROTOCOL, AETHER_PROTOCOL_LABELS, AETHER_PROTOCOL_VALUES);
                        currentProxyInfo.aetherScan = aetherFieldValue(FIELD_AETHER_SCAN, AETHER_SCAN_LABELS, AETHER_SCAN_VALUES);
                        currentProxyInfo.aetherIp = aetherFieldValue(FIELD_AETHER_IP, AETHER_IP_LABELS, AETHER_IP_VALUES);
                        currentProxyInfo.aetherTransport = aetherFieldValue(FIELD_AETHER_TRANSPORT, AETHER_TRANSPORT_LABELS, AETHER_TRANSPORT_VALUES);
                        currentProxyInfo.aetherFragment = "1".equals(aetherFieldValue(FIELD_AETHER_FRAGMENT, AETHER_FRAGMENT_LABELS, AETHER_FRAGMENT_VALUES));
                        currentProxyInfo.aetherNoize = aetherFieldValue(FIELD_AETHER_NOIZE, AETHER_NOIZE_LABELS, AETHER_NOIZE_VALUES);
                        currentProxyInfo.aetherQuickReconnect = "1".equals(aetherFieldValue(FIELD_AETHER_QUICK_RECONNECT, AETHER_BOOL_LABELS, AETHER_BOOL_VALUES));
                        currentProxyInfo.aetherPeers = inputFields[FIELD_AETHER_PEERS].getText().toString();
                        currentProxyInfo.proxyName = inputFields[FIELD_AETHER_NAME].getText().toString();
                        currentProxyInfo.normalizeAetherFields();
                    }

                    SharedPreferences preferences = MessagesController.getGlobalMainSettings();
                    SharedPreferences.Editor editor = preferences.edit();
                    boolean enabled;
                    if (addingNewProxy) {
                        SharedConfig.addProxy(currentProxyInfo);
                        SharedConfig.currentProxy = currentProxyInfo;
                        editor.putBoolean("proxy_enabled", true);
                        enabled = true;
                    } else {
                        enabled = preferences.getBoolean("proxy_enabled", false);
                        SharedConfig.saveProxyList();
                    }
                    if (addingNewProxy || SharedConfig.currentProxy == currentProxyInfo) {
                        currentProxyInfo.settings.toSharedPreferences(editor);
                        ConnectionsManager.setProxySettings(enabled, currentProxyInfo.settings);
                    }
                    editor.commit();

                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);

                    finishFragment();
                }
            }
        });

        doneItem = actionBar.createMenu().addItemWithWidth(done_button, R.drawable.ic_ab_done, AndroidUtilities.dp(56));
        doneItem.setContentDescription(LocaleController.getString(R.string.Done));

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        linearLayout2 = new SectionsScrollView.SectionsLinearLayout(context) {
            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                // todo: update to recyclerview
                super.dispatchDraw(canvas);
                invalidate();
            }
        };
        SectionsScrollView sectionsScrollView;
        scrollView = sectionsScrollView = new SectionsScrollView(context, linearLayout2, resourceProvider) {
            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                super.dispatchDraw(canvas);
                invalidate();
            }
        };
        actionBar.setAdaptiveBackground(sectionsScrollView);
        scrollView.setFillViewport(true);
        AndroidUtilities.setScrollViewEdgeEffectColor(scrollView, Theme.getColor(Theme.key_actionBarDefault));
        frameLayout.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        linearLayout2.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(linearLayout2, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final View.OnClickListener typeCellClickListener = view -> setProxyType(ProxySettings.intToType((Integer) view.getTag()), true);

        for (int a = 0; a < 6; a++) {
            ProxySettings.Type t = ProxySettings.intToType(a);

            typeCell[a] = new RadioCell(context);
            typeCell[a].setBackground(Theme.getSelectorDrawable(true));
            typeCell[a].setTag(a);
            if (a == 0) {
                typeCell[a].setText(LocaleController.getString(R.string.UseProxySocks5), t == currentType, true);
            } else if (a == 1) {
                typeCell[a].setText(LocaleController.getString(R.string.UseProxyTelegram), t == currentType, true);
            } else if (a == 2) {
                typeCell[a].setText(LocaleController.getString(R.string.UseProxyWeb), t == currentType, true);
            } else if (a == 3) {
                typeCell[a].setText(LocaleController.getString(R.string.UseProxyXray), t == currentType, true);
            } else if (a == 4) {
                typeCell[a].setText(LocaleController.getString(R.string.UseProxyAether), t == currentType, false);
            } else {
                typeCell[a].setText(LocaleController.getString(R.string.UseProxyRedirectIp), t == currentType, false);
            }
            if (a == 4 && !AetherProxyManager.isSupportedDevice()) {
                // No Aether binary for this ABI (e.g. x86): hide the option entirely.
                typeCell[a].setVisibility(View.GONE);
            }
            linearLayout2.addView(typeCell[a], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
            typeCell[a].setOnClickListener(typeCellClickListener);
        }

        sectionCell[0] = new ShadowSectionCell(context);
        linearLayout2.addView(sectionCell[0], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        inputFieldsContainer = new LinearLayout(context);
        inputFieldsContainer.setOrientation(LinearLayout.VERTICAL);
         // inputFieldsContainer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));


        // bring to front for transitions
        inputFieldsContainer.setElevation(AndroidUtilities.dp(1f));
        inputFieldsContainer.setOutlineProvider(null);
        linearLayout2.addView(inputFieldsContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        inputFields = new EditTextBoldCursor[FIELD_COUNT];
        for (int a = 0; a < FIELD_COUNT; a++) {
            FrameLayout container = new FrameLayout(context);
            inputFieldsContainer.addView(container, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, a == FIELD_XRAY_CONFIG ? 320 : 64));

            inputFields[a] = new EditTextBoldCursor(context);
            inputFields[a].setTag(a);
            inputFields[a].setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            inputFields[a].setHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
            inputFields[a].setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            inputFields[a].setBackground(null);
            inputFields[a].setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            inputFields[a].setCursorSize(AndroidUtilities.dp(20));
            inputFields[a].setCursorWidth(1.5f);
            inputFields[a].setSingleLine(true);
            inputFields[a].setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            inputFields[a].setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
            inputFields[a].setTransformHintToHeader(true);
            inputFields[a].setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));

            if (a == FIELD_IP) {
                inputFields[a].setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_URI);
                inputFields[a].addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {

                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {

                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                        checkShareDone(true);
                    }
                });
            } else if (a == FIELD_PORT) {
                inputFields[a].setInputType(InputType.TYPE_CLASS_NUMBER);
                inputFields[a].addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {

                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {

                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                        if (ignoreOnTextChange) {
                            return;
                        }
                        EditText phoneField = inputFields[FIELD_PORT];
                        int start = phoneField.getSelectionStart();
                        String chars = "0123456789";
                        String str = phoneField.getText().toString();
                        StringBuilder builder = new StringBuilder(str.length());
                        for (int a = 0; a < str.length(); a++) {
                            String ch = str.substring(a, a + 1);
                            if (chars.contains(ch)) {
                                builder.append(ch);
                            }
                        }
                        ignoreOnTextChange = true;
                        boolean changed;
                        int port = Utilities.parseInt(builder.toString());
                        if (port < 0 || port > 65535 || !str.equals(builder.toString())) {
                            if (port < 0) {
                                phoneField.setText("0");
                            } else if (port > 65535) {
                                phoneField.setText("65535");
                            } else {
                                phoneField.setText(builder.toString());
                            }
                        } else {
                            if (start >= 0) {
                                phoneField.setSelection(Math.min(start, phoneField.length()));
                            }
                        }
                        ignoreOnTextChange = false;
                        checkShareDone(true);
                    }
                });
            } else if (a == FIELD_PASSWORD) {
                inputFields[a].setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                inputFields[a].setTypeface(Typeface.DEFAULT);
                inputFields[a].setTransformationMethod(PasswordTransformationMethod.getInstance());
            } else if (a == FIELD_XRAY_CONFIG) {
                inputFields[a].setSingleLine(false);
                inputFields[a].setMinLines(4);
                inputFields[a].setMaxLines(14);
                inputFields[a].setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP);
                inputFields[a].setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                inputFields[a].setVerticalScrollBarEnabled(true);
                inputFields[a].setLineColors(0x00000000, 0x00000000, 0x00000000);
                inputFields[a].setOnTouchListener((v, event) -> {
                    // let the JSON text scroll inside the field instead of the page
                    if (v.hasFocus()) {
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return false;
                });
                inputFields[a].addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                        checkShareDone(true);
                    }
                });
            } else {
                inputFields[a].setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            }
            if (a == FIELD_SECRET) {
                inputFields[a].addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                        checkShareDone(true);
                    }
                });
            }
            inputFields[a].setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            switch (a) {
                case FIELD_IP:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyAddress));
                    inputFields[a].setText(currentProxyInfo.settings.getAddress());
                    break;
                case FIELD_PASSWORD:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyPassword));
                    inputFields[a].setText(currentProxyInfo.settings.getPassword());
                    break;
                case FIELD_PORT:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyPort));
                    inputFields[a].setText(Integer.toString(currentProxyInfo.settings.getPort()));
                    break;
                case FIELD_USER:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyUsername));
                    inputFields[a].setText(currentProxyInfo.settings.getUser());
                    break;
                case FIELD_SECRET:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxySecret));
                    inputFields[a].setText(currentProxyInfo.settings.getSecret());
                    break;
                case FIELD_XRAY_CONFIG:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyXrayConfig));
                    inputFields[a].setText(currentProxyInfo.xrayConfig);
                    break;
                case FIELD_AETHER_PROTOCOL:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyAetherProtocol));
                    inputFields[a].setText(aetherFieldLabel(currentProxyInfo.aetherProtocol, AETHER_PROTOCOL_LABELS, AETHER_PROTOCOL_VALUES));
                    setupAetherDropdown(a, LocaleController.getString(R.string.UseProxyAetherProtocol), AETHER_PROTOCOL_LABELS, AETHER_PROTOCOL_VALUES);
                    break;
                case FIELD_AETHER_SCAN:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyAetherScan));
                    inputFields[a].setText(aetherFieldLabel(currentProxyInfo.aetherScan, AETHER_SCAN_LABELS, AETHER_SCAN_VALUES));
                    setupAetherDropdown(a, LocaleController.getString(R.string.UseProxyAetherScan), AETHER_SCAN_LABELS, AETHER_SCAN_VALUES);
                    break;
                case FIELD_AETHER_IP:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyAetherIp));
                    inputFields[a].setText(aetherFieldLabel(currentProxyInfo.aetherIp, AETHER_IP_LABELS, AETHER_IP_VALUES));
                    setupAetherDropdown(a, LocaleController.getString(R.string.UseProxyAetherIp), AETHER_IP_LABELS, AETHER_IP_VALUES);
                    break;
                case FIELD_AETHER_TRANSPORT:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyAetherTransport));
                    inputFields[a].setText(aetherFieldLabel(currentProxyInfo.aetherTransport, AETHER_TRANSPORT_LABELS, AETHER_TRANSPORT_VALUES));
                    setupAetherDropdown(a, LocaleController.getString(R.string.UseProxyAetherTransport), AETHER_TRANSPORT_LABELS, AETHER_TRANSPORT_VALUES);
                    break;
                case FIELD_AETHER_FRAGMENT:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyAetherFragment));
                    inputFields[a].setText(aetherFieldLabel(currentProxyInfo.aetherFragment ? "1" : "", AETHER_FRAGMENT_LABELS, AETHER_FRAGMENT_VALUES));
                    setupAetherDropdown(a, LocaleController.getString(R.string.UseProxyAetherFragment), AETHER_FRAGMENT_LABELS, AETHER_FRAGMENT_VALUES);
                    break;
                case FIELD_AETHER_NOIZE:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyAetherNoize));
                    inputFields[a].setText(aetherFieldLabel(currentProxyInfo.aetherNoize, AETHER_NOIZE_LABELS, AETHER_NOIZE_VALUES));
                    setupAetherDropdown(a, LocaleController.getString(R.string.UseProxyAetherNoize), AETHER_NOIZE_LABELS, AETHER_NOIZE_VALUES);
                    break;
                case FIELD_AETHER_QUICK_RECONNECT:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyAetherQuickReconnect));
                    inputFields[a].setText(aetherFieldLabel(currentProxyInfo.aetherQuickReconnect ? "1" : "", AETHER_BOOL_LABELS, AETHER_BOOL_VALUES));
                    setupAetherDropdown(a, LocaleController.getString(R.string.UseProxyAetherQuickReconnect), AETHER_BOOL_LABELS, AETHER_BOOL_VALUES);
                    break;
                case FIELD_AETHER_PEERS:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyAetherPeers));
                    inputFields[a].setText(currentProxyInfo.aetherPeers);
                    break;
                case FIELD_AETHER_NAME:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyAetherName));
                    inputFields[a].setText(currentProxyInfo.proxyName);
                    break;
            }
            inputFields[a].setSelection(inputFields[a].length());

            inputFields[a].setPadding(0, 0, 0, 0);
            container.addView(inputFields[a], LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 17, a == FIELD_IP ? 12 : 0, 17, 0));

            inputFields[a].setOnEditorActionListener((textView, i, keyEvent) -> {
                if (i == EditorInfo.IME_ACTION_NEXT) {
                    int num = (Integer) textView.getTag();
                    if (num + 1 < inputFields.length) {
                        num++;
                        inputFields[num].requestFocus();
                    }
                    return true;
                } else if (i == EditorInfo.IME_ACTION_DONE) {
                    finishFragment();
                    return true;
                }
                return false;
            });
        }

        for (int i = 0; i < 3; i++) {
            bottomCells[i] = new TextInfoPrivacyCell(context);
            bottomCells[i].setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
            if (i == 0) {
                bottomCells[i].setText(LocaleController.getString(R.string.UseProxyInfo));
            } else if (i == 1) {
                bottomCells[i].setText(LocaleController.getString(R.string.UseProxyTelegramInfo) + "\n\n" + LocaleController.getString(R.string.UseProxyTelegramInfo2));
                bottomCells[i].setVisibility(View.GONE);
            } else {
                bottomCells[i].setText(LocaleController.getString(R.string.UseProxyXrayInfo));
                bottomCells[i].setVisibility(View.GONE);
            }
            linearLayout2.addView(bottomCells[i], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        pasteCell = new TextSettingsCell(fragmentView.getContext());
        pasteCell.setBackground(Theme.getSelectorDrawable(true));
        pasteCell.setText(LocaleController.getString(R.string.PasteFromClipboard), false);
        pasteCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        pasteCell.setOnClickListener(v -> {
            if (pasteProxySettings != null) {
                final ProxySettings.Type pasteType = pasteProxySettings.getType();
                final SharedConfig.ProxyInfo pasteInfo = pasteProxyInfo;

                for (int i = 0; i < inputFields.length; i++) {
                    if (pasteType == ProxySettings.Type.SOCKS5 && i == FIELD_SECRET) {
                        continue;
                    }
                    if (pasteType == ProxySettings.Type.MTPROTO && (i == FIELD_USER || i == FIELD_PASSWORD)) {
                        continue;
                    }
                    if (pasteType == ProxySettings.Type.XRAY && (i == FIELD_USER || i == FIELD_PASSWORD || i == FIELD_SECRET)) {
                        continue;
                    }

                    String field = null;
                    if (i == FIELD_IP) {
                        field = pasteProxySettings.getAddress();
                    } else if (i == FIELD_PORT) {
                        final int port = pasteProxySettings.getPort();
                        field = port != 0 ? Integer.toString(port) : null;
                    } else if (i == FIELD_USER) {
                        field = pasteProxySettings.getUser();
                    } else if (i == FIELD_PASSWORD) {
                        field = pasteProxySettings.getPassword();
                    } else if (i == FIELD_SECRET) {
                        field = pasteProxySettings.getSecret();
                    } else if (pasteType == ProxySettings.Type.XRAY && pasteInfo != null) {
                        if (i == FIELD_XRAY_CONFIG) {
                            field = pasteInfo.xrayConfig;
                        }
                    }

                    if (!TextUtils.isEmpty(field)) {
                        try {
                            inputFields[i].setText(URLDecoder.decode(field, "UTF-8"));
                        } catch (UnsupportedEncodingException e) {
                            inputFields[i].setText(field);
                        }
                    } else {
                        inputFields[i].setText(null);
                    }
                }
                inputFields[0].setSelection(inputFields[0].length());
                setProxyType(pasteType, true, () -> {
                    AndroidUtilities.hideKeyboard(inputFieldsContainer.findFocus());
                    for (int i = 0; i < inputFields.length; i++) {
                        if (i == FIELD_IP) {
                            continue;
                        }
                        if (pasteType == ProxySettings.Type.WEB) {
                            if (i == FIELD_SECRET) {
                                continue;
                            }
                        }
                        if (pasteType == ProxySettings.Type.MTPROTO) {
                            if (i == FIELD_SECRET || i == FIELD_PORT) {
                                continue;
                            }
                        }
                        if (pasteType == ProxySettings.Type.SOCKS5) {
                            if (i == FIELD_PORT || i == FIELD_USER || i == FIELD_PASSWORD) {
                                continue;
                            }
                        }
                        if (pasteType == ProxySettings.Type.XRAY) {
                            if (i == FIELD_XRAY_CONFIG) {
                                continue;
                            }
                        }
                        inputFields[i].setText(null);
                    }
                });
            }
        });
        linearLayout2.addView(pasteCell, 0, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        pasteCell.setVisibility(View.GONE);
        sectionCell[2] = new ShadowSectionCell(fragmentView.getContext());
        linearLayout2.addView(sectionCell[2], 1, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        sectionCell[2].setVisibility(View.GONE);

        shareCell = new TextSettingsCell(context);
        shareCell.setBackgroundDrawable(Theme.getSelectorDrawable(true));
        shareCell.setText(LocaleController.getString(R.string.ShareFile), false);
        shareCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        linearLayout2.addView(shareCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        shareCell.setOnClickListener(v -> {
            StringBuilder params = new StringBuilder();
            String address = inputFields[FIELD_IP].getText().toString();
            String password = inputFields[FIELD_PASSWORD].getText().toString();
            String user = inputFields[FIELD_USER].getText().toString();
            String port = inputFields[FIELD_PORT].getText().toString();
            String secret = inputFields[FIELD_SECRET].getText().toString();
            String url;
            try {
                if (!TextUtils.isEmpty(address)) {
                    params.append("server=").append(URLEncoder.encode(address, "UTF-8"));
                }
                if (!TextUtils.isEmpty(port)) {
                    if (params.length() != 0) {
                        params.append("&");
                    }
                    params.append("port=").append(URLEncoder.encode(port, "UTF-8"));
                }
                if (currentType == ProxySettings.Type.MTPROTO) {
                    url = "https://t.me/proxy?";
                    if (params.length() != 0) {
                        params.append("&");
                    }
                    params.append("secret=").append(URLEncoder.encode(secret, "UTF-8"));
                } else {
                    url = "https://t.me/socks?";
                    if (!TextUtils.isEmpty(user)) {
                        if (params.length() != 0) {
                            params.append("&");
                        }
                        params.append("user=").append(URLEncoder.encode(user, "UTF-8"));
                    }
                    if (!TextUtils.isEmpty(password)) {
                        if (params.length() != 0) {
                            params.append("&");
                        }
                        params.append("pass=").append(URLEncoder.encode(password, "UTF-8"));
                    }
                }
            } catch (Exception ignore) {
                return;
            }
            if (params.length() == 0) {
                return;
            }
            String link = url + params.toString();
            QRCodeBottomSheet alert = new QRCodeBottomSheet(context, LocaleController.getString(R.string.ShareQrCode), link, LocaleController.getString(R.string.QRCodeLinkHelpProxy), true);
            Bitmap icon = SvgHelper.getBitmap(AndroidUtilities.readRes(R.raw.qr_dog), AndroidUtilities.dp(60), AndroidUtilities.dp(60), false);
            alert.setCenterImage(icon);
            showDialog(alert);
        });

        redownloadCell = new TextSettingsCell(context);
        redownloadCell.setBackgroundDrawable(Theme.getSelectorDrawable(true));
        redownloadCell.setText(LocaleController.getString(R.string.XrayProxyRedownload), false);
        redownloadCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        redownloadCell.setVisibility(View.GONE);
        linearLayout2.addView(redownloadCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        redownloadCell.setOnClickListener(v -> {
            if (currentType == ProxySettings.Type.XRAY) {
                boolean deleted = XrayProxyManager.deleteCoreFiles();
                if (getParentActivity() != null) {
                    if (deleted) {
                        Toast.makeText(getParentActivity(), LocaleController.getString(R.string.XrayProxyRedownloaded), Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(getParentActivity(), LocaleController.getString(R.string.XrayProxyRedownloadFailed), Toast.LENGTH_SHORT).show();
                    }
                }
                SharedPreferences preferences = MessagesController.getGlobalMainSettings();
                boolean enabled = preferences.getBoolean("proxy_enabled", false);
                if (enabled && SharedConfig.currentProxy != null && SharedConfig.currentProxy.isXray()) {
                    XrayProxyManager.startService();
                    ConnectionsManager.setProxySettings(true, SharedConfig.currentProxy.settings);
                }
            } else if (currentType == ProxySettings.Type.AETHER) {
                boolean deleted = AetherProxyManager.deleteCoreFiles();
                if (getParentActivity() != null) {
                    if (deleted) {
                        Toast.makeText(getParentActivity(), LocaleController.getString(R.string.AetherProxyRedownloaded), Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(getParentActivity(), LocaleController.getString(R.string.AetherProxyRedownloadFailed), Toast.LENGTH_SHORT).show();
                    }
                }
                SharedPreferences preferences = MessagesController.getGlobalMainSettings();
                boolean enabled = preferences.getBoolean("proxy_enabled", false);
                if (enabled && SharedConfig.currentProxy != null && SharedConfig.currentProxy.isAether()) {
                    AetherProxyManager.startService();
                    ConnectionsManager.setProxySettings(true, SharedConfig.currentProxy.settings);
                }
            }
        });

        importConfigCell = new TextSettingsCell(context);
        importConfigCell.setBackgroundDrawable(Theme.getSelectorDrawable(true));
        importConfigCell.setText(LocaleController.getString(R.string.XrayProxyImportConfig), false);
        importConfigCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        importConfigCell.setVisibility(View.GONE);
        linearLayout2.addView(importConfigCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        importConfigCell.setOnClickListener(v -> {
            if (getParentActivity() == null) {
                return;
            }
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent, IMPORT_CONFIG_REQUEST);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });

        xrayLogCell = new TextSettingsCell(context);
        xrayLogCell.setBackgroundDrawable(Theme.getSelectorDrawable(true));
        xrayLogCell.setText(LocaleController.getString(R.string.XrayProxyViewLog), false);
        xrayLogCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        xrayLogCell.setVisibility(View.GONE);
        linearLayout2.addView(xrayLogCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        xrayLogCell.setOnClickListener(v -> showXrayLog());

        redirectImportCell = new TextSettingsCell(context);
        redirectImportCell.setBackgroundDrawable(Theme.getSelectorDrawable(true));
        redirectImportCell.setText(LocaleController.getString(R.string.XrayProxyImportIps), false);
        redirectImportCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        redirectImportCell.setVisibility(View.GONE);
        linearLayout2.addView(redirectImportCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        redirectImportCell.setOnClickListener(v -> {
            if (getParentActivity() == null) {
                return;
            }
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent, IMPORT_IPS_REQUEST);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });

        xrayStatusCell = new TextSettingsCell(context);
        xrayStatusCell.setBackgroundDrawable(Theme.getSelectorDrawable(true));
        xrayStatusCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        xrayStatusCell.setVisibility(View.GONE);
        xrayStatusCell.setEnabled(false);
        linearLayout2.addView(xrayStatusCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        aetherStatusCell = new TextSettingsCell(context);
        aetherStatusCell.setBackgroundDrawable(Theme.getSelectorDrawable(true));
        aetherStatusCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        aetherStatusCell.setVisibility(View.GONE);
        aetherStatusCell.setEnabled(false);
        linearLayout2.addView(aetherStatusCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        sectionCell[1] = new ShadowSectionCell(context);
        linearLayout2.addView(sectionCell[1], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);

        shareDoneEnabled = true;
        shareDoneProgress = 1f;
        checkShareDone(false);

        currentType = null;
        setProxyType(currentProxyInfo.settings.getType(), false);

        pasteProxySettings = null;
        pasteString = null;
        updatePasteCell();

        return fragmentView;
    }

    private void updatePasteCell() {
        final ClipData clip = clipboardManager.getPrimaryClip();

        String clipText;
        if (clip != null && clip.getItemCount() > 0) {
            try {
                clipText = clip.getItemAt(0).coerceToText(fragmentView.getContext()).toString();
            } catch (Exception e) {
                clipText = null;
            }
        } else {
            clipText = null;
        }

        if (TextUtils.equals(clipText, pasteString)) {
            return;
        }

        pasteProxySettings = null;
        pasteProxyInfo = null;
        pasteString = clipText;
        if (clipText != null) {
            String trimmedClip = clipText.trim();
            if (trimmedClip.startsWith("{") && XrayProxyManager.validateConfig(trimmedClip) == null) {
                SharedConfig.ProxyInfo info = new SharedConfig.ProxyInfo(
                        ProxySettings.builder()
                                .setType(ProxySettings.Type.XRAY)
                                .setAddress("xray")
                                .setPort(0)
                                .build()
                );
                info.xrayConfig = trimmedClip;
                this.pasteProxyInfo = info;
                this.pasteProxySettings = info.settings;
            } else {
                ProxySettings pasteProxySettings = null;
                try {
                    pasteProxySettings = ProxySettings.fromUri(Uri.parse(clipText));
                } catch (Exception ignoreE) {

                }

                if (pasteProxySettings != null && pasteProxySettings.isValid()) {
                    this.pasteProxySettings = pasteProxySettings;
                }
            }
        }

        if (pasteProxySettings != null) {
            if (pasteCell.getVisibility() != View.VISIBLE) {
                pasteCell.setVisibility(View.VISIBLE);
                sectionCell[2].setVisibility(View.VISIBLE);
            }
        } else {
            if (pasteCell.getVisibility() != View.GONE) {
                pasteCell.setVisibility(View.GONE);
                sectionCell[2].setVisibility(View.GONE);
            }
        }
    }

    private void setShareDoneEnabled(boolean enabled, boolean animated) {
        if (shareDoneEnabled != enabled) {
            if (shareDoneAnimator != null) {
                shareDoneAnimator.cancel();
            } else if (animated) {
                shareDoneAnimator = ValueAnimator.ofFloat(0f, 1f);
                shareDoneAnimator.setDuration(200);
                shareDoneAnimator.addUpdateListener(a -> {
                    shareDoneProgress = AndroidUtilities.lerp(shareDoneProgressAnimValues, a.getAnimatedFraction());
                    shareCell.setTextColor(ColorUtils.blendARGB(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2), Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4), shareDoneProgress));
                    doneItem.setAlpha(shareDoneProgress / 2f + 0.5f);
                });
            }
            if (animated) {
                shareDoneProgressAnimValues[0] = shareDoneProgress;
                shareDoneProgressAnimValues[1] = enabled ? 1f : 0f;
                shareDoneAnimator.start();
            } else {
                shareDoneProgress = enabled ? 1f : 0f;
                shareCell.setTextColor(enabled ? Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4) : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
                doneItem.setAlpha(enabled ? 1f : .5f);
            }
            shareCell.setEnabled(enabled);
            doneItem.setEnabled(enabled);
            shareDoneEnabled = enabled;
        }
    }

    private void updateXrayStatusCell() {
        if (xrayStatusCell == null || currentType != ProxySettings.Type.XRAY) {
            return;
        }
        String title = LocaleController.getString(R.string.XrayProxyCoreStatus);
        String value;
        int state = XrayProxyManager.getState();
        if (state == XrayProxyManager.STATE_RUNNING) {
            value = LocaleController.getString(R.string.XrayProxyStatusReady);
        } else if (state == XrayProxyManager.STATE_STARTING) {
            value = LocaleController.getString(R.string.XrayProxyStatusStarting);
        } else if (state == XrayProxyManager.STATE_DOWNLOADING) {
            long total = XrayProxyManager.getDownloadTotalBytes();
            long current = XrayProxyManager.getDownloadBytes();
            if (total > 0) {
                int percent = (int) Math.min(100, (current * 100) / total);
                value = LocaleController.formatString(R.string.XrayProxyStatusDownloadingPercent, percent);
            } else {
                value = LocaleController.getString(R.string.XrayProxyStatusDownloading);
            }
        } else if (state == XrayProxyManager.STATE_FAILED) {
            value = LocaleController.getString(R.string.XrayProxyStatusFailed);
        } else {
            value = LocaleController.getString(R.string.XrayProxyStatusIdle);
        }
        xrayStatusCell.setTextAndValue(title, value, false);
    }

    private void updateAetherStatusCell() {
        if (aetherStatusCell == null || currentType != ProxySettings.Type.AETHER) {
            return;
        }
        String title = LocaleController.getString(R.string.AetherProxyCoreStatus);
        String value;
        int state = AetherProxyManager.getState();
        if (state == AetherProxyManager.STATE_RUNNING) {
            value = LocaleController.getString(R.string.AetherProxyStatusReady);
        } else if (state == AetherProxyManager.STATE_STARTING) {
            value = LocaleController.getString(R.string.AetherProxyStatusStarting);
        } else if (state == AetherProxyManager.STATE_DOWNLOADING) {
            long total = AetherProxyManager.getDownloadTotalBytes();
            long current = AetherProxyManager.getDownloadBytes();
            if (total > 0) {
                int percent = (int) Math.min(100, (current * 100) / total);
                value = LocaleController.formatString(R.string.AetherProxyStatusDownloadingPercent, percent);
            } else {
                value = LocaleController.getString(R.string.AetherProxyStatusDownloading);
            }
        } else if (state == AetherProxyManager.STATE_FAILED) {
            value = LocaleController.getString(R.string.AetherProxyStatusFailed);
        } else {
            value = LocaleController.getString(R.string.AetherProxyStatusIdle);
        }
        aetherStatusCell.setTextAndValue(title, value, false);
    }

    private String aetherFieldLabel(String value, String[] labels, String[] values) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return labels[i];
            }
        }
        return labels[0];
    }

    private String aetherFieldValue(int field, String[] labels, String[] values) {
        if (inputFields[field] == null) {
            return values[0];
        }
        String label = inputFields[field].getText().toString();
        for (int i = 0; i < labels.length; i++) {
            if (labels[i].equals(label)) {
                return values[i];
            }
        }
        return values[0];
    }

    private void setupAetherDropdown(int field, String title, String[] labels, String[] values) {
        EditTextBoldCursor editText = inputFields[field];
        editText.setFocusable(false);
        editText.setFocusableInTouchMode(false);
        editText.setClickable(true);
        editText.setOnClickListener(v -> {
            int checked = 0;
            String current = editText.getText().toString();
            for (int i = 0; i < labels.length; i++) {
                if (labels[i].equals(current)) {
                    checked = i;
                    break;
                }
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle(title);
            CharSequence[] displayLabels = new CharSequence[labels.length];
            for (int i = 0; i < labels.length; i++) {
                displayLabels[i] = i == checked ? "✓ " + labels[i] : labels[i];
            }
            builder.setItems(displayLabels, (dialog, which) -> {
                editText.setText(labels[which]);
                checkShareDone(true);
                dialog.dismiss();
            });
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            showDialog(builder.create());
        });
    }

    private void checkShareDone(boolean animated) {
        if (shareCell == null || doneItem == null || inputFields[FIELD_IP] == null || inputFields[FIELD_PORT] == null) {
            return;
        }
        boolean enabled;
        if (currentType == ProxySettings.Type.WEB) {
            enabled = !TextUtils.isEmpty(WebProxyTransport.normalizeHost(inputFields[FIELD_IP].getText().toString()))
                    && WebProxyTransport.isValidSecret(inputFields[FIELD_SECRET].getText().toString());
        } else if (currentType == ProxySettings.Type.XRAY) {
            enabled = XrayProxyManager.validateConfig(inputFields[FIELD_XRAY_CONFIG].getText().toString()) == null;
        } else if (currentType == ProxySettings.Type.AETHER) {
            enabled = true;
        } else if (currentType == ProxySettings.Type.REDIRECT_IP) {
            enabled = GeminiTranslator.isValidIpv4(inputFields[FIELD_IP].getText().toString().trim());
        } else {
            enabled = inputFields[FIELD_IP].length() != 0
                    && Utilities.parseInt(inputFields[FIELD_PORT].getText()) != 0;
        }
        setShareDoneEnabled(enabled, animated);
    }

    private void setProxyType(ProxySettings.Type type, boolean animated) {
        setProxyType(type, animated, null);
    }

    private void setProxyType(ProxySettings.Type type, boolean animated, Runnable onTransitionEnd) {
        if (currentType != type) {
            currentType = type;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                TransitionManager.endTransitions(linearLayout2);
            }
            if (animated) {
                final TransitionSet transitionSet = new TransitionSet()
                        .addTransition(new Fade(Fade.OUT))
                        .addTransition(new ChangeBounds())
                        .addTransition(new Fade(Fade.IN))
                        .setInterpolator(CubicBezierInterpolator.DEFAULT)
                        .setDuration(250);

                if (onTransitionEnd != null) {
                    transitionSet.addListener(new Transition.TransitionListener() {
                        @Override
                        public void onTransitionStart(Transition transition) {
                        }

                        @Override
                        public void onTransitionEnd(Transition transition) {
                            onTransitionEnd.run();
                        }

                        @Override
                        public void onTransitionCancel(Transition transition) {
                        }

                        @Override
                        public void onTransitionPause(Transition transition) {
                        }

                        @Override
                        public void onTransitionResume(Transition transition) {
                        }
                    });
                }

                TransitionManager.beginDelayedTransition(linearLayout2, transitionSet);
            }
            boolean isXray = currentType == ProxySettings.Type.XRAY;
            boolean isAether = currentType == ProxySettings.Type.AETHER;
            boolean isRedirect = currentType == ProxySettings.Type.REDIRECT_IP;
            // Aether needs no server address/port: the engine dials Cloudflare itself.
            ((View) inputFields[FIELD_IP].getParent()).setVisibility(isAether || isXray ? View.GONE : View.VISIBLE);
            redirectImportCell.setVisibility(View.GONE);
            if (currentType == ProxySettings.Type.SOCKS5) {
                bottomCells[0].setVisibility(View.VISIBLE);
                bottomCells[1].setVisibility(View.GONE);
                bottomCells[2].setVisibility(View.GONE);
                ((View) inputFields[FIELD_SECRET].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_PASSWORD].getParent()).setVisibility(View.VISIBLE);
                ((View) inputFields[FIELD_USER].getParent()).setVisibility(View.VISIBLE);
                ((View) inputFields[FIELD_PORT].getParent()).setVisibility(View.VISIBLE);
            } else if (currentType == ProxySettings.Type.MTPROTO) {
                bottomCells[0].setVisibility(View.GONE);
                bottomCells[1].setVisibility(View.VISIBLE);
                bottomCells[2].setVisibility(View.GONE);
                bottomCells[1].setText(LocaleController.getString(R.string.UseProxyTelegramInfo) + "\n\n" + LocaleController.getString(R.string.UseProxyTelegramInfo2));
                ((View) inputFields[FIELD_SECRET].getParent()).setVisibility(View.VISIBLE);
                ((View) inputFields[FIELD_PASSWORD].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_USER].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_PORT].getParent()).setVisibility(View.VISIBLE);
            } else if (currentType == ProxySettings.Type.WEB) {
                bottomCells[0].setVisibility(View.GONE);
                bottomCells[1].setVisibility(View.VISIBLE);
                bottomCells[2].setVisibility(View.GONE);
                bottomCells[1].setText(LocaleController.getString(R.string.UseProxyWebInfo));
                ((View) inputFields[FIELD_SECRET].getParent()).setVisibility(View.VISIBLE);
                ((View) inputFields[FIELD_PASSWORD].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_USER].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_PORT].getParent()).setVisibility(View.GONE);
                inputFields[FIELD_PORT].setText("443");
            } else if (isXray) {
                bottomCells[0].setVisibility(View.GONE);
                bottomCells[1].setVisibility(View.GONE);
                bottomCells[2].setVisibility(View.VISIBLE);
                bottomCells[2].setText(LocaleController.getString(R.string.UseProxyXrayInfo));
                redownloadCell.setText(LocaleController.getString(R.string.XrayProxyRedownload), false);
                redownloadCell.setVisibility(View.VISIBLE);
                xrayStatusCell.setVisibility(View.VISIBLE);
                updateXrayStatusCell();
                if (aetherStatusCell != null) {
                    aetherStatusCell.setVisibility(View.GONE);
                }
                ((View) inputFields[FIELD_SECRET].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_PASSWORD].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_USER].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_PORT].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_XRAY_CONFIG].getParent()).setVisibility(View.VISIBLE);
                importConfigCell.setVisibility(View.VISIBLE);
                xrayLogCell.setVisibility(View.VISIBLE);
                redirectImportCell.setVisibility(View.GONE);
            } else if (isAether) {
                bottomCells[0].setVisibility(View.GONE);
                bottomCells[1].setVisibility(View.GONE);
                bottomCells[2].setVisibility(View.VISIBLE);
                bottomCells[2].setText(LocaleController.getString(R.string.UseProxyAetherInfo));
                redownloadCell.setText(LocaleController.getString(R.string.AetherProxyRedownload), false);
                redownloadCell.setVisibility(View.VISIBLE);
                xrayStatusCell.setVisibility(View.GONE);
                if (aetherStatusCell != null) {
                    aetherStatusCell.setVisibility(View.VISIBLE);
                    updateAetherStatusCell();
                }
                ((View) inputFields[FIELD_SECRET].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_PASSWORD].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_USER].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_PORT].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_XRAY_CONFIG].getParent()).setVisibility(View.GONE);
                importConfigCell.setVisibility(View.GONE);
                xrayLogCell.setVisibility(View.GONE);
                redirectImportCell.setVisibility(View.GONE);
            } else if (isRedirect) {
                redirectImportCell.setVisibility(View.VISIBLE);
                redownloadCell.setVisibility(View.GONE);
                xrayStatusCell.setVisibility(View.GONE);
                if (aetherStatusCell != null) {
                    aetherStatusCell.setVisibility(View.GONE);
                }
                importConfigCell.setVisibility(View.GONE);
                xrayLogCell.setVisibility(View.GONE);
                ((View) inputFields[FIELD_XRAY_CONFIG].getParent()).setVisibility(View.GONE);
                bottomCells[0].setVisibility(View.GONE);
                bottomCells[1].setVisibility(View.GONE);
                bottomCells[2].setVisibility(View.VISIBLE);
                bottomCells[2].setText(LocaleController.getString(R.string.UseProxyRedirectIpInfo));
                ((View) inputFields[FIELD_SECRET].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_PASSWORD].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_USER].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_PORT].getParent()).setVisibility(View.VISIBLE);
            }
            for (int f = FIELD_AETHER_PROTOCOL; f <= FIELD_AETHER_NAME; f++) {
                ((View) inputFields[f].getParent()).setVisibility(isAether ? View.VISIBLE : View.GONE);
            }
            if (!isXray && !isAether && !isRedirect) {
                redownloadCell.setVisibility(View.GONE);
                xrayStatusCell.setVisibility(View.GONE);
                importConfigCell.setVisibility(View.GONE);
                xrayLogCell.setVisibility(View.GONE);
                ((View) inputFields[FIELD_XRAY_CONFIG].getParent()).setVisibility(View.GONE);
                if (aetherStatusCell != null) {
                    aetherStatusCell.setVisibility(View.GONE);
                }
            }
            if (!isXray) {
                xrayStatusCell.setVisibility(View.GONE);
            }
            if (!isAether && aetherStatusCell != null) {
                aetherStatusCell.setVisibility(View.GONE);
            }
            shareCell.setVisibility(currentType == ProxySettings.Type.WEB || currentType == ProxySettings.Type.XRAY || currentType == ProxySettings.Type.AETHER || currentType == ProxySettings.Type.REDIRECT_IP ? View.GONE : View.VISIBLE);
            typeCell[0].setChecked(currentType == ProxySettings.Type.SOCKS5, animated);
            typeCell[1].setChecked(currentType == ProxySettings.Type.MTPROTO, animated);
            typeCell[2].setChecked(currentType == ProxySettings.Type.WEB, animated);
            typeCell[3].setChecked(currentType == ProxySettings.Type.XRAY, animated);
            typeCell[4].setChecked(currentType == ProxySettings.Type.AETHER, animated);
            typeCell[5].setChecked(currentType == ProxySettings.Type.REDIRECT_IP, animated);
            checkShareDone(animated);
        }
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        if (isOpen && !backward && addingNewProxy) {
            inputFields[FIELD_IP].requestFocus();
            AndroidUtilities.showKeyboard(inputFields[FIELD_IP]);
        }
    }

    private void showXrayLog() {
        if (getParentActivity() == null) {
            return;
        }
        String current = XrayProxyManager.getLogText();
        final String log = current == null || current.trim().isEmpty()
                ? LocaleController.getString(R.string.XrayProxyLogEmpty)
                : current;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.XrayProxyViewLog));
        ScrollView scroll = new ScrollView(getParentActivity());
        TextView textView = new TextView(getParentActivity());
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        textView.setTypeface(Typeface.MONOSPACE);
        textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        textView.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(10), AndroidUtilities.dp(20), AndroidUtilities.dp(10));
        textView.setText(log);
        scroll.addView(textView);
        builder.setView(scroll);
        builder.setPositiveButton(LocaleController.getString(R.string.Copy), (dialog, which) -> {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("xray-log", log));
            } catch (Throwable ignored) {}
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Close), null);
        showDialog(builder.create());
    }

    private void importRedirectIps(Uri uri) {
        ArrayList<SharedConfig.ProxyInfo> added = new ArrayList<>();
        try {
            StringBuilder sb = new StringBuilder();
            try (java.io.InputStream in = getParentActivity().getContentResolver().openInputStream(uri);
                 java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            for (String rawLine : sb.toString().split("\n")) {
                String entry = rawLine.trim();
                if (entry.isEmpty() || entry.startsWith("#")) {
                    continue;
                }
                String ip = entry;
                int port = 0;
                int colon = entry.lastIndexOf(':');
                if (colon > 0) {
                    ip = entry.substring(0, colon).trim();
                    String portText = entry.substring(colon + 1).trim();
                    try {
                        port = Integer.parseInt(portText);
                    } catch (NumberFormatException ignored) {
                        port = -1;
                    }
                }
                if (!GeminiTranslator.isValidIpv4(ip) || port < 0 || port > 65535) {
                    continue;
                }
                SharedConfig.ProxyInfo info = new SharedConfig.ProxyInfo(
                        ProxySettings.builder()
                                .setType(ProxySettings.Type.REDIRECT_IP)
                                .setAddress(ip)
                                .setPort(port)
                                .build()
                );
                SharedConfig.ProxyInfo existing = SharedConfig.addProxy(info);
                if (existing == info) {
                    added.add(info);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (getParentActivity() != null) {
            String message = added.isEmpty()
                    ? LocaleController.getString(R.string.XrayProxyNoValidIps)
                    : LocaleController.formatString(R.string.XrayProxyImportedCount, added.size());
            Toast.makeText(getParentActivity(), message, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode == IMPORT_IPS_REQUEST) {
            importRedirectIps(data.getData());
            return;
        }
        if (requestCode != IMPORT_CONFIG_REQUEST) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            try (java.io.InputStream in = getParentActivity().getContentResolver().openInputStream(data.getData());
                 java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            String json = sb.toString().trim();
            if (XrayProxyManager.validateConfig(json) != null) {
                if (getParentActivity() != null) {
                    Toast.makeText(getParentActivity(), LocaleController.getString(R.string.XrayProxyInvalidConfig), Toast.LENGTH_SHORT).show();
                }
                return;
            }
            inputFields[FIELD_XRAY_CONFIG].setText(json);
            checkShareDone(true);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        final ThemeDescription.ThemeDescriptionDelegate delegate = () -> {
            if (shareCell != null && (shareDoneAnimator == null || !shareDoneAnimator.isRunning())) {
                shareCell.setTextColor(shareDoneEnabled ? Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4) : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            }
            if (inputFields != null) {
                for (int i = 0; i < inputFields.length; i++) {
                    if (i == FIELD_XRAY_CONFIG) {
                        continue;
                    }
                    inputFields[i].setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField),
                            Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated),
                            Theme.getColor(Theme.key_text_RedRegular));
                }
            }
        };
        ArrayList<ThemeDescription> arrayList = new ArrayList<>();
        arrayList.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        arrayList.add(new ThemeDescription(scrollView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SEARCH, null, null, null, null, Theme.key_actionBarDefaultSearch));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SEARCHPLACEHOLDER, null, null, null, null, Theme.key_actionBarDefaultSearchPlaceholder));
        arrayList.add(new ThemeDescription(inputFieldsContainer, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));
        arrayList.add(new ThemeDescription(linearLayout2, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));

        arrayList.add(new ThemeDescription(shareCell, ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_windowBackgroundWhite));
        arrayList.add(new ThemeDescription(shareCell, ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_listSelector));
        arrayList.add(new ThemeDescription(null, 0, null, null, null, null, delegate, Theme.key_windowBackgroundWhiteBlueText4));
        arrayList.add(new ThemeDescription(null, 0, null, null, null, null, delegate, Theme.key_windowBackgroundWhiteGrayText2));

        arrayList.add(new ThemeDescription(pasteCell, ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_windowBackgroundWhite));
        arrayList.add(new ThemeDescription(pasteCell, ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_listSelector));
        arrayList.add(new ThemeDescription(pasteCell, 0, new Class[]{TextSettingsCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueText4));

        for (int a = 0; a < typeCell.length; a++) {
            arrayList.add(new ThemeDescription(typeCell[a], ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_windowBackgroundWhite));
            arrayList.add(new ThemeDescription(typeCell[a], ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_listSelector));
            arrayList.add(new ThemeDescription(typeCell[a], 0, new Class[]{RadioCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
            arrayList.add(new ThemeDescription(typeCell[a], ThemeDescription.FLAG_CHECKBOX, new Class[]{RadioCell.class}, new String[]{"radioButton"}, null, null, null, Theme.key_radioBackground));
            arrayList.add(new ThemeDescription(typeCell[a], ThemeDescription.FLAG_CHECKBOXCHECK, new Class[]{RadioCell.class}, new String[]{"radioButton"}, null, null, null, Theme.key_radioBackgroundChecked));
        }

        if (inputFields != null) {
            for (int a = 0; a < inputFields.length; a++) {
                arrayList.add(new ThemeDescription(inputFields[a], ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
                arrayList.add(new ThemeDescription(inputFields[a], ThemeDescription.FLAG_HINTTEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteHintText));
                arrayList.add(new ThemeDescription(inputFields[a], ThemeDescription.FLAG_HINTTEXTCOLOR | ThemeDescription.FLAG_PROGRESSBAR, null, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
                arrayList.add(new ThemeDescription(inputFields[a], ThemeDescription.FLAG_CURSORCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
                arrayList.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_windowBackgroundWhiteInputField));
                arrayList.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_windowBackgroundWhiteInputFieldActivated));
                arrayList.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_text_RedRegular));
            }
        } else {
            arrayList.add(new ThemeDescription(null, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
            arrayList.add(new ThemeDescription(null, ThemeDescription.FLAG_HINTTEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteHintText));
        }
        arrayList.add(new ThemeDescription(headerCell, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));
        arrayList.add(new ThemeDescription(headerCell, 0, new Class[]{HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
        for (int a = 0; a < sectionCell.length; a++) {
            if (sectionCell[a] != null) {
                arrayList.add(new ThemeDescription(sectionCell[a], ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{ShadowSectionCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
            }
        }
        for (int i = 0; i < bottomCells.length; i++) {
            arrayList.add(new ThemeDescription(bottomCells[i], ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{TextInfoPrivacyCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
            arrayList.add(new ThemeDescription(bottomCells[i], 0, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));
            arrayList.add(new ThemeDescription(bottomCells[i], ThemeDescription.FLAG_LINKCOLOR, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteLinkText));
        }

        return arrayList;
    }
}
