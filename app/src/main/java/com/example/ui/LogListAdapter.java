package com.example.ui;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.example.R;
import com.example.data.ProxyLogFormatter;
import com.example.model.ProxyLogEntry;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RecyclerView adapter displaying connection, DNS, full packet/request sending,
 * and firewall log entries with source app icon and yyyy-MM-dd HH:mm:ss.SSS timestamp.
 */
public class LogListAdapter extends ListAdapter<ProxyLogEntry, LogListAdapter.LogViewHolder> {

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private static final Map<String, Drawable> ICON_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, String> LABEL_CACHE = new ConcurrentHashMap<>();

    public LogListAdapter() {
        super(DIFF_CALLBACK);
    }

    private static final DiffUtil.ItemCallback<ProxyLogEntry> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<ProxyLogEntry>() {
                @Override
                public boolean areItemsTheSame(@NonNull ProxyLogEntry oldItem, @NonNull ProxyLogEntry newItem) {
                    return oldItem.getId() == newItem.getId();
                }

                @Override
                public boolean areContentsTheSame(@NonNull ProxyLogEntry oldItem, @NonNull ProxyLogEntry newItem) {
                    return oldItem.getTimestamp() == newItem.getTimestamp()
                            && oldItem.getLevel() == newItem.getLevel()
                            && oldItem.getMessage().equals(newItem.getMessage())
                            && oldItem.getPackageName().equals(newItem.getPackageName())
                            && oldItem.getEventTag().equals(newItem.getEventTag());
                }
            };

    @NonNull
    @Override
    public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_log_entry, parent, false);
        return new LogViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    public static class LogViewHolder extends RecyclerView.ViewHolder {
        private final ImageView ivAppIcon;
        private final TextView tvTime;
        private final TextView tvLevel;
        private final TextView tvAppName;
        private final TextView tvMessage;

        LogViewHolder(@NonNull View itemView) {
            super(itemView);
            ivAppIcon = itemView.findViewById(R.id.iv_log_app_icon);
            tvTime = itemView.findViewById(R.id.tv_log_time);
            tvLevel = itemView.findViewById(R.id.tv_log_level);
            tvAppName = itemView.findViewById(R.id.tv_log_app_name);
            tvMessage = itemView.findViewById(R.id.tv_log_message);
        }

        void bind(ProxyLogEntry entry) {
            Context ctx = itemView.getContext();
            tvTime.setText(TIME_FORMAT.format(new Date(entry.getTimestamp())));
            String tag = entry.getEffectiveTag();
            tvLevel.setText(tag);
            tvMessage.setText(ProxyLogFormatter.localizeForDisplay(ctx, entry.getMessage()));

            bindAppIdentity(ctx, entry.getPackageName());

            int textColor;
            int bgColor;
            if ("DNS".equals(tag) || "TCP".equals(tag) || "UDP".equals(tag)) {
                textColor = R.color.md_theme_primary;
                bgColor = R.color.md_theme_primaryContainer;
            } else if ("SEND".equals(tag) || "DONE".equals(tag) || "HTTPS".equals(tag) || "HTTP".equals(tag)) {
                textColor = entry.getLevel() == ProxyLogEntry.Level.ERROR
                        ? R.color.status_error
                        : (entry.getLevel() == ProxyLogEntry.Level.SUCCESS
                                ? R.color.status_connected
                                : R.color.md_theme_secondary);
                bgColor = entry.getLevel() == ProxyLogEntry.Level.ERROR
                        ? R.color.status_error_bg
                        : (entry.getLevel() == ProxyLogEntry.Level.SUCCESS
                                ? R.color.status_connected_bg
                                : R.color.md_theme_secondaryContainer);
            } else {
                switch (entry.getLevel()) {
                    case SUCCESS:
                        textColor = R.color.status_connected;
                        bgColor = R.color.status_connected_bg;
                        break;
                    case WARN:
                        textColor = R.color.status_switching;
                        bgColor = R.color.status_switching_bg;
                        break;
                    case ERROR:
                        textColor = R.color.status_error;
                        bgColor = R.color.status_error_bg;
                        break;
                    case INFO:
                    default:
                        textColor = R.color.status_idle;
                        bgColor = R.color.status_idle_bg;
                        break;
                }
            }
            tvLevel.setTextColor(ContextCompat.getColor(ctx, textColor));
            tvLevel.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(ctx, bgColor)));
        }

        private void bindAppIdentity(Context ctx, String rawPackage) {
            String targetPkg = (rawPackage != null && !rawPackage.trim().isEmpty())
                    ? rawPackage.trim()
                    : ctx.getPackageName();

            Drawable icon = ICON_CACHE.get(targetPkg);
            String label = LABEL_CACHE.get(targetPkg);

            if (icon == null || label == null) {
                PackageManager pm = ctx.getPackageManager();
                try {
                    ApplicationInfo info = pm.getApplicationInfo(targetPkg, 0);
                    icon = info.loadIcon(pm);
                    CharSequence rawLabel = info.loadLabel(pm);
                    label = rawLabel != null ? rawLabel.toString().trim() : targetPkg;
                } catch (Exception e) {
                    try {
                        icon = pm.getApplicationIcon(ctx.getPackageName());
                    } catch (Exception ignored) {
                        icon = ContextCompat.getDrawable(ctx, R.drawable.ic_shield);
                    }
                    label = targetPkg;
                }
                if (icon != null) {
                    ICON_CACHE.put(targetPkg, icon);
                }
                if (label != null) {
                    LABEL_CACHE.put(targetPkg, label);
                }
            }

            if (icon != null) {
                ivAppIcon.setImageDrawable(icon);
            } else {
                ivAppIcon.setImageResource(R.drawable.ic_shield);
            }

            if (rawPackage != null && !rawPackage.trim().isEmpty() && label != null && !label.isEmpty()) {
                tvAppName.setVisibility(View.VISIBLE);
                tvAppName.setText(label);
            } else {
                tvAppName.setVisibility(View.GONE);
            }
        }
    }
}
