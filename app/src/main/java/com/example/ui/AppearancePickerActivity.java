package com.example.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;

import com.example.R;
import com.example.data.SettingsPreferences;
import com.example.data.SettingsPreferences.AppLanguage;
import com.example.data.SettingsPreferences.ThemeMode;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;

import java.util.Locale;

/**
 * Dedicated full-screen selector for Light/Dark Theme and Display Language.
 * Language selection displays a prominent "Follow System Language" card at the top
 * followed by a 2-column (3-column on tablets) grid of flag & native-name tiles.
 */
public class AppearancePickerActivity extends AppCompatActivity {

    public static final String EXTRA_PICKER_MODE = "extra_picker_mode";
    public static final String MODE_THEME = "theme";
    public static final String MODE_LANGUAGE = "language";

    @NonNull
    public static Intent createIntent(@NonNull Context context, @NonNull String mode) {
        Intent intent = new Intent(context, AppearancePickerActivity.class);
        intent.putExtra(EXTRA_PICKER_MODE, mode);
        return intent;
    }

    private SettingsPreferences settingsPrefs;
    private String pickerMode = MODE_LANGUAGE;
    private LinearLayout optionsContainer;
    private NestedScrollView scrollView;
    private MaterialCardView cardPickerBanner;
    private MaterialCardView cardSystemLanguage;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_appearance_picker);

        settingsPrefs = new SettingsPreferences(this);
        String extraMode = getIntent() != null ? getIntent().getStringExtra(EXTRA_PICKER_MODE) : null;
        pickerMode = MODE_THEME.equals(extraMode) ? MODE_THEME : MODE_LANGUAGE;

        MaterialToolbar toolbar = findViewById(R.id.appearance_picker_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        View root = findViewById(R.id.appearance_picker_root);
        scrollView = findViewById(R.id.scroll_appearance_picker);
        cardPickerBanner = findViewById(R.id.card_picker_banner);
        cardSystemLanguage = findViewById(R.id.card_system_language);
        ImageView ivHeaderIcon = findViewById(R.id.iv_picker_header_icon);
        TextView tvHeaderTitle = findViewById(R.id.tv_picker_header_title);
        TextView tvHeaderSubtitle = findViewById(R.id.tv_picker_header_subtitle);
        optionsContainer = findViewById(R.id.container_picker_options);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(
                    toolbar.getPaddingLeft(),
                    sysBars.top,
                    toolbar.getPaddingRight(),
                    toolbar.getPaddingBottom()
            );
            scrollView.setPadding(
                    sysBars.left,
                    scrollView.getPaddingTop(),
                    sysBars.right,
                    sysBars.bottom
            );
            return WindowInsetsCompat.CONSUMED;
        });

        if (MODE_THEME.equals(pickerMode)) {
            toolbar.setTitle(R.string.section_theme);
            cardPickerBanner.setVisibility(View.VISIBLE);
            cardSystemLanguage.setVisibility(View.GONE);
            ivHeaderIcon.setImageResource(R.drawable.ic_palette);
            tvHeaderTitle.setText(R.string.section_theme);
            tvHeaderSubtitle.setText(R.string.section_theme_subtitle);
            renderThemeOptions();
        } else {
            toolbar.setTitle(R.string.section_language);
            cardPickerBanner.setVisibility(View.GONE);
            cardSystemLanguage.setVisibility(View.VISIBLE);
            renderLanguageGrid();
        }
    }

    private void renderThemeOptions() {
        optionsContainer.removeAllViews();
        ThemeMode current = settingsPrefs.getThemeMode();
        ThemeMode[] modes = new ThemeMode[]{
                ThemeMode.SYSTEM_TIME,
                ThemeMode.LIGHT,
                ThemeMode.DARK
        };
        for (ThemeMode mode : modes) {
            int iconRes;
            String title;
            String subtitle;
            if (mode == ThemeMode.LIGHT) {
                iconRes = R.drawable.ic_light_mode;
                title = getString(R.string.theme_light);
                subtitle = null;
            } else if (mode == ThemeMode.DARK) {
                iconRes = R.drawable.ic_dark_mode;
                title = getString(R.string.theme_dark);
                subtitle = null;
            } else {
                iconRes = R.drawable.ic_timer;
                title = getString(R.string.theme_system_short);
                subtitle = getString(R.string.theme_system);
            }
            SettingsUiHelper.addPickerOptionCard(
                    this,
                    optionsContainer,
                    iconRes,
                    null,
                    title,
                    subtitle,
                    mode == current,
                    () -> {
                        settingsPrefs.setThemeMode(mode);
                        finish();
                    }
            );
        }
    }

    private void renderLanguageGrid() {
        AppLanguage current = settingsPrefs.getAppLanguage();
        bindSystemLanguageCard(current == AppLanguage.SYSTEM);

        optionsContainer.removeAllViews();
        AppLanguage[] languages = new AppLanguage[]{
                AppLanguage.VI,
                AppLanguage.EN,
                AppLanguage.KO,
                AppLanguage.JA,
                AppLanguage.DE,
                AppLanguage.ES,
                AppLanguage.ID,
                AppLanguage.IT,
                AppLanguage.AR,
                AppLanguage.HI,
                AppLanguage.PT
        };

        int columns = Math.max(2, getResources().getInteger(R.integer.language_grid_columns));
        float density = getResources().getDisplayMetrics().density;
        int gapPx = Math.round(10 * density);
        LayoutInflater inflater = LayoutInflater.from(this);

        View selectedChild = null;
        LinearLayout currentRow = null;

        for (int i = 0; i < languages.length; i++) {
            if (i % columns == 0) {
                currentRow = new LinearLayout(this);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                currentRow.setBaselineAligned(false);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                rowLp.bottomMargin = gapPx;
                optionsContainer.addView(currentRow, rowLp);
            }

            AppLanguage lang = languages[i];
            boolean isSelected = lang == current;
            MaterialCardView tile = createLanguageTile(inflater, currentRow, lang, isSelected);

            LinearLayout.LayoutParams tileLp = (LinearLayout.LayoutParams) tile.getLayoutParams();
            if ((i % columns) < columns - 1) {
                tileLp.setMarginEnd(gapPx);
            }
            tile.setLayoutParams(tileLp);
            currentRow.addView(tile);

            if (isSelected) {
                selectedChild = currentRow;
            }
        }

        // Pad the trailing incomplete row so the final card stays column-aligned
        if (currentRow != null) {
            int remainder = languages.length % columns;
            if (remainder != 0) {
                int missing = columns - remainder;
                for (int j = 0; j < missing; j++) {
                    Space spacer = new Space(this);
                    LinearLayout.LayoutParams spacerLp = new LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                    if (j < missing - 1) {
                        spacerLp.setMarginEnd(gapPx);
                    }
                    currentRow.addView(spacer, spacerLp);
                }
            }
        }

        if (selectedChild != null) {
            final View targetRow = selectedChild;
            scrollView.post(() -> {
                int y = Math.max(0, targetRow.getTop() - Math.round(16 * density));
                if (y > scrollView.getHeight() / 2) {
                    scrollView.scrollTo(0, y);
                }
            });
        }
    }

    private void bindSystemLanguageCard(boolean selected) {
        MaterialCardView leadingCard = cardSystemLanguage.findViewById(R.id.card_system_lang_leading);
        ImageView ivIcon = cardSystemLanguage.findViewById(R.id.iv_system_lang_icon);
        TextView tvTitle = cardSystemLanguage.findViewById(R.id.tv_system_lang_title);
        TextView tvSubtitle = cardSystemLanguage.findViewById(R.id.tv_system_lang_subtitle);
        ImageView ivSelected = cardSystemLanguage.findViewById(R.id.iv_system_lang_selected);

        int strokeUnit = Math.max(1, Math.round(getResources().getDisplayMetrics().density));
        if (selected) {
            cardSystemLanguage.setCardBackgroundColor(ContextCompat.getColor(this, R.color.md_theme_primaryContainer));
            cardSystemLanguage.setStrokeColor(ContextCompat.getColor(this, R.color.md_theme_primary));
            cardSystemLanguage.setStrokeWidth(2 * strokeUnit);
            leadingCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.md_theme_primary));
            ivIcon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.md_theme_onPrimary)));
            tvTitle.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onPrimaryContainer));
            tvSubtitle.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onPrimaryContainer));
            ivSelected.setImageResource(R.drawable.ic_check_circle);
            ivSelected.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.md_theme_primary)));
        } else {
            cardSystemLanguage.setCardBackgroundColor(ContextCompat.getColor(this, R.color.md_theme_surface));
            cardSystemLanguage.setStrokeColor(ContextCompat.getColor(this, R.color.md_theme_outline));
            cardSystemLanguage.setStrokeWidth(strokeUnit);
            leadingCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.md_theme_surfaceVariant));
            ivIcon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.md_theme_onSurfaceVariant)));
            tvTitle.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurface));
            tvSubtitle.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurfaceVariant));
            ivSelected.setImageResource(R.drawable.ic_radio_unchecked);
            ivSelected.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.md_theme_outline)));
        }

        cardSystemLanguage.setContentDescription(getString(R.string.lang_system));
        cardSystemLanguage.setOnClickListener(v -> {
            settingsPrefs.setAppLanguage(AppLanguage.SYSTEM);
            finish();
        });
    }

    @NonNull
    private MaterialCardView createLanguageTile(@NonNull LayoutInflater inflater,
                                                @NonNull LinearLayout parentRow,
                                                @NonNull AppLanguage lang,
                                                boolean selected) {
        MaterialCardView tile = (MaterialCardView) inflater.inflate(
                R.layout.item_language_grid_card, parentRow, false);

        TextView tvBadge = tile.findViewById(R.id.tv_lang_badge);
        ImageView ivSelected = tile.findViewById(R.id.iv_lang_selected);
        TextView tvNative = tile.findViewById(R.id.tv_lang_native);
        TextView tvSubtitle = tile.findViewById(R.id.tv_lang_subtitle);

        String fullLabel = SettingsUiHelper.formatLanguageLabel(this, lang);
        String nativeTitle = fullLabel;
        String subTitle = "";
        int openParen = fullLabel.indexOf(" (");
        if (openParen > 0 && fullLabel.endsWith(")")) {
            nativeTitle = fullLabel.substring(0, openParen).trim();
            subTitle = fullLabel.substring(openParen + 2, fullLabel.length() - 1).trim();
        }

        tvBadge.setText(getFlagAndCodeBadge(lang));
        tvNative.setText(nativeTitle);
        if (!subTitle.isEmpty()) {
            tvSubtitle.setText(subTitle);
            tvSubtitle.setVisibility(View.VISIBLE);
        } else {
            tvSubtitle.setVisibility(View.GONE);
        }

        int strokeUnit = Math.max(1, Math.round(getResources().getDisplayMetrics().density));
        if (selected) {
            tile.setCardBackgroundColor(ContextCompat.getColor(this, R.color.md_theme_primaryContainer));
            tile.setStrokeColor(ContextCompat.getColor(this, R.color.md_theme_primary));
            tile.setStrokeWidth(2 * strokeUnit);
            tvBadge.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.md_theme_primary)));
            tvBadge.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onPrimary));
            tvNative.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onPrimaryContainer));
            tvSubtitle.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onPrimaryContainer));
            ivSelected.setImageResource(R.drawable.ic_check_circle);
            ivSelected.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.md_theme_primary)));
        } else {
            tile.setCardBackgroundColor(ContextCompat.getColor(this, R.color.md_theme_surface));
            tile.setStrokeColor(ContextCompat.getColor(this, R.color.md_theme_outline));
            tile.setStrokeWidth(strokeUnit);
            tvBadge.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.md_theme_surfaceVariant)));
            tvBadge.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurfaceVariant));
            tvNative.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurface));
            tvSubtitle.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurfaceVariant));
            ivSelected.setImageResource(R.drawable.ic_radio_unchecked);
            ivSelected.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.md_theme_outline)));
        }

        tile.setContentDescription(fullLabel);
        tile.setOnClickListener(v -> {
            settingsPrefs.setAppLanguage(lang);
            finish();
        });
        return tile;
    }

    @NonNull
    private static String getFlagAndCodeBadge(@NonNull AppLanguage lang) {
        switch (lang) {
            case VI:
                return "\uD83C\uDDFB\uD83C\uDDF3 VI";
            case EN:
                return "\uD83C\uDDFA\uD83C\uDDF8 EN";
            case KO:
                return "\uD83C\uDDF0\uD83C\uDDF7 KO";
            case JA:
                return "\uD83C\uDDEF\uD83C\uDDF5 JA";
            case DE:
                return "\uD83C\uDDE9\uD83C\uDDEA DE";
            case ES:
                return "\uD83C\uDDEA\uD83C\uDDF8 ES";
            case ID:
                return "\uD83C\uDDEE\uD83C\uDDE9 ID";
            case IT:
                return "\uD83C\uDDEE\uD83C\uDDF9 IT";
            case AR:
                return "\uD83C\uDDF8\uD83C\uDDE6 AR";
            case HI:
                return "\uD83C\uDDEE\uD83C\uDDF3 HI";
            case PT:
                return "\uD83C\uDDF5\uD83C\uDDF9 PT";
            case SYSTEM:
            default:
                return lang.getTag().toUpperCase(Locale.ROOT);
        }
    }
}
