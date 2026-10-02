package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextRadioCell;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

public class DirectSettingsActivity extends BaseFragment {

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_SWITCH = 1;
    private static final int VIEW_TYPE_RADIO = 2;
    private static final int VIEW_TYPE_INFO = 3;
    private static final int VIEW_TYPE_SHADOW = 4;

    private static final int ROW_HEADER = 0;
    private static final int ROW_MODIFY = 1;
    private static final int ROW_PORT_HEADER = 2;
    private static final int ROW_AUTO = 3;
    private static final int ROW_HTTPS = 4;
    private static final int ROW_HTTP = 5;
    private static final int ROW_SHADOW = 6;
    private static final int ROW_INFO = 7;
    private static final int ROW_COUNT = 8;

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
    }

    private boolean isModifyEnabled() {
        return prefs().getBoolean("direct_port_modify", false);
    }

    private int getMode() {
        int mode = prefs().getInt("direct_port_mode", ConnectionsManager.DIRECT_PORT_AUTO);
        if (mode < ConnectionsManager.DIRECT_PORT_AUTO || mode > ConnectionsManager.DIRECT_PORT_HTTP) {
            return ConnectionsManager.DIRECT_PORT_AUTO;
        }
        return mode;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.DirectSettings));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        FrameLayout frameLayout = (FrameLayout) fragmentView;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);
        DefaultItemAnimator itemAnimator = new DefaultItemAnimator() {
            @Override
            protected void onMoveAnimationUpdate(RecyclerView.ViewHolder holder) {
                listView.invalidate();
            }
        };
        itemAnimator.setDurations(200);
        itemAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        listView.setItemAnimator(itemAnimator);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            if (position == ROW_MODIFY && view instanceof TextCheckCell) {
                boolean value = !isModifyEnabled();
                prefs().edit().putBoolean("direct_port_modify", value).apply();
                ConnectionsManager.applyDirectPortMode();
                ((TextCheckCell) view).setChecked(value);
                listAdapter.notifyDataSetChanged();
            } else if ((position == ROW_AUTO || position == ROW_HTTPS || position == ROW_HTTP) && view instanceof TextRadioCell) {
                if (!isModifyEnabled()) {
                    return;
                }
                int mode = position == ROW_HTTPS ? ConnectionsManager.DIRECT_PORT_HTTPS : position == ROW_HTTP ? ConnectionsManager.DIRECT_PORT_HTTP : ConnectionsManager.DIRECT_PORT_AUTO;
                prefs().edit().putInt("direct_port_mode", mode).apply();
                ConnectionsManager.applyDirectPortMode();
                listAdapter.notifyDataSetChanged();
            }
        });

        return fragmentView;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context mContext;

        ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return ROW_COUNT;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == ROW_HEADER || position == ROW_PORT_HEADER) return VIEW_TYPE_HEADER;
            if (position == ROW_MODIFY) return VIEW_TYPE_SWITCH;
            if (position == ROW_AUTO || position == ROW_HTTPS || position == ROW_HTTP) return VIEW_TYPE_RADIO;
            if (position == ROW_SHADOW) return VIEW_TYPE_SHADOW;
            return VIEW_TYPE_INFO;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_HEADER:
                    view = new HeaderCell(mContext);
                    break;
                case VIEW_TYPE_SWITCH:
                    view = new TextCheckCell(mContext);
                    break;
                case VIEW_TYPE_RADIO:
                    view = new TextRadioCell(mContext);
                    break;
                case VIEW_TYPE_INFO:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case VIEW_TYPE_SHADOW:
                default:
                    view = new ShadowSectionCell(mContext);
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    if (position == ROW_PORT_HEADER) {
                        cell.setText(LocaleController.getString(R.string.DirectPort));
                    } else {
                        cell.setText(LocaleController.getString(R.string.DirectSettings));
                    }
                    break;
                }
                case VIEW_TYPE_SWITCH: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    cell.setTextAndCheck(LocaleController.getString(R.string.ModifyDirectPort), isModifyEnabled(), true);
                    break;
                }
                case VIEW_TYPE_RADIO: {
                    TextRadioCell cell = (TextRadioCell) holder.itemView;
                    boolean enabled = isModifyEnabled();
                    int mode = getMode();
                    if (position == ROW_AUTO) {
                        cell.setTextAndValueAndCheck(LocaleController.getString(R.string.DirectPortAuto), "", mode == ConnectionsManager.DIRECT_PORT_AUTO, false, true);
                    } else if (position == ROW_HTTPS) {
                        cell.setTextAndValueAndCheck(LocaleController.getString(R.string.DirectPortHttps), "443", mode == ConnectionsManager.DIRECT_PORT_HTTPS, false, true);
                    } else {
                        cell.setTextAndValueAndCheck(LocaleController.getString(R.string.DirectPortHttp), "80", mode == ConnectionsManager.DIRECT_PORT_HTTP, false, false);
                    }
                    cell.setEnabled(enabled);
                    cell.setAlpha(enabled ? 1f : 0.5f);
                    break;
                }
                case VIEW_TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setText(LocaleController.getString(R.string.DirectSettingsInfo));
                    break;
                }
            }
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == VIEW_TYPE_SWITCH || type == VIEW_TYPE_RADIO;
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{TextCheckCell.class, TextRadioCell.class, HeaderCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));
        return themeDescriptions;
    }
}
