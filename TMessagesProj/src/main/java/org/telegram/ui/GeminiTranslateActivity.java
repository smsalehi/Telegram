package org.telegram.ui;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.GeminiTranslator;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

public class GeminiTranslateActivity extends BaseFragment {

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_SWITCH = 1;
    private static final int VIEW_TYPE_SETTINGS = 2;
    private static final int VIEW_TYPE_INFO = 3;
    private static final int VIEW_TYPE_SHADOW = 4;

    private static final int ROW_HEADER = 0;
    private static final int ROW_ENABLE = 1;
    private static final int ROW_MODEL = 2;
    private static final int ROW_API_KEY = 3;
    private static final int ROW_PROMPT = 4;
    private static final int ROW_SHADOW = 5;
    private static final int ROW_INFO = 6;
    private static final int ROW_COUNT = 7;

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    @Override
    public boolean onFragmentCreate() {
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.GeminiTranslate));
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
            if (position == ROW_ENABLE && view instanceof TextCheckCell) {
                boolean value = !GeminiTranslator.isEnabled();
                if (value && GeminiTranslator.getApiKey().isEmpty()) {
                    openApiKeyDialog(true);
                    return;
                }
                GeminiTranslator.setEnabled(value);
                ((TextCheckCell) view).setChecked(value);
                listAdapter.notifyItemChanged(ROW_API_KEY);
            } else if (position == ROW_API_KEY) {
                openApiKeyDialog(false);
            } else if (position == ROW_MODEL) {
                openModelDialog();
            } else if (position == ROW_PROMPT) {
                openPromptDialog();
            }
        });

        return fragmentView;
    }

    private void openApiKeyDialog(boolean enableOnSave) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.GeminiApiKey));

        LinearLayout layout = new LinearLayout(getParentActivity());
        layout.setOrientation(LinearLayout.VERTICAL);
        final EditTextBoldCursor editText = new EditTextBoldCursor(getParentActivity());
        editText.setTextSize(16);
        editText.setText(GeminiTranslator.getApiKey());
        editText.setHint(LocaleController.getString(R.string.GeminiApiKeyHint));
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        editText.setSelection(editText.getText() != null ? editText.getText().length() : 0);
        layout.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, Gravity.LEFT | Gravity.TOP, 24, 6, 24, 0));
        builder.setView(layout);

        builder.setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
            String key = editText.getText() != null ? editText.getText().toString().trim() : "";
            GeminiTranslator.setApiKey(key);
            if (enableOnSave && !key.isEmpty()) {
                GeminiTranslator.setEnabled(true);
            }
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void openModelDialog() {
        if (getParentActivity() == null) {
            return;
        }
        final AlertDialog progressDialog = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progressDialog.show();
        GeminiTranslator.fetchModels(models -> {
            try {
                progressDialog.dismiss();
            } catch (Throwable ignored) {}
            if (getParentActivity() == null) {
                return;
            }
            final String[] items;
            if (models != null && !models.isEmpty()) {
                items = models.toArray(new String[0]);
            } else {
                items = GeminiTranslator.MODELS;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle(LocaleController.getString(R.string.GeminiModel));
            builder.setItems(items, (dialog, which) -> {
                if (which >= 0 && which < items.length) {
                    GeminiTranslator.setModel(items[which]);
                    if (listAdapter != null) {
                        listAdapter.notifyItemChanged(ROW_MODEL);
                    }
                }
            });
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            showDialog(builder.create());
        });
    }

    private void openPromptDialog() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.GeminiPrompt));

        LinearLayout layout = new LinearLayout(getParentActivity());
        layout.setOrientation(LinearLayout.VERTICAL);
        final EditTextBoldCursor editText = new EditTextBoldCursor(getParentActivity());
        editText.setTextSize(14);
        editText.setText(GeminiTranslator.getPrompt());
        editText.setHint("{text} {from} {to}");
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editText.setMinLines(4);
        layout.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 24, 6, 24, 0));
        builder.setView(layout);

        builder.setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
            String prompt = editText.getText() != null ? editText.getText().toString().trim() : "";
            GeminiTranslator.setPrompt(prompt);
            if (listAdapter != null) {
                listAdapter.notifyItemChanged(ROW_PROMPT);
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
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
            if (position == ROW_HEADER) return VIEW_TYPE_HEADER;
            if (position == ROW_ENABLE) return VIEW_TYPE_SWITCH;
            if (position == ROW_MODEL || position == ROW_API_KEY || position == ROW_PROMPT) return VIEW_TYPE_SETTINGS;
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
                case VIEW_TYPE_SETTINGS:
                    view = new TextSettingsCell(mContext);
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
                    cell.setText(LocaleController.getString(R.string.GeminiTranslate));
                    break;
                }
                case VIEW_TYPE_SWITCH: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    cell.setTextAndCheck(LocaleController.getString(R.string.GeminiTranslateEnable), GeminiTranslator.isEnabled(), true);
                    break;
                }
                case VIEW_TYPE_SETTINGS: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == ROW_MODEL) {
                        cell.setTextAndValue(LocaleController.getString(R.string.GeminiModel), GeminiTranslator.getModel(), true);
                    } else if (position == ROW_PROMPT) {
                        cell.setTextAndValue(LocaleController.getString(R.string.GeminiPrompt), GeminiTranslator.isCustomPrompt() ? LocaleController.getString(R.string.GeminiCustom) : LocaleController.getString(R.string.GeminiDefault), true);
                    } else {
                        String key = GeminiTranslator.getApiKey();
                        cell.setTextAndValue(LocaleController.getString(R.string.GeminiApiKey), key.isEmpty() ? LocaleController.getString(R.string.GeminiNotSet) : GeminiTranslator.getMaskedKey(), true);
                    }
                    break;
                }
                case VIEW_TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setText(LocaleController.getString(R.string.GeminiTranslateInfo));
                    break;
                }
            }
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == VIEW_TYPE_SWITCH || type == VIEW_TYPE_SETTINGS;
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{TextCheckCell.class, TextSettingsCell.class, HeaderCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));
        return themeDescriptions;
    }
}
