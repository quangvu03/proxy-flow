package com.example.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.example.R;
import com.example.model.ProxyLogEntry;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * RecyclerView adapter displaying connection, testing, and auto-switch log entries.
 */
public class LogListAdapter extends ListAdapter<ProxyLogEntry, LogListAdapter.LogViewHolder> {

    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

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
                            && oldItem.getMessage().equals(newItem.getMessage());
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
        private final TextView tvTime;
        private final TextView tvLevel;
        private final TextView tvMessage;

        LogViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTime = itemView.findViewById(R.id.tv_log_time);
            tvLevel = itemView.findViewById(R.id.tv_log_level);
            tvMessage = itemView.findViewById(R.id.tv_log_message);
        }

        void bind(ProxyLogEntry entry) {
            Context ctx = itemView.getContext();
            tvTime.setText(TIME_FORMAT.format(new Date(entry.getTimestamp())));
            tvLevel.setText(entry.getLevel().name());
            tvMessage.setText(entry.getMessage());

            int textColor;
            int bgColor;
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
            tvLevel.setTextColor(ContextCompat.getColor(ctx, textColor));
            tvLevel.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(ctx, bgColor)));
        }
    }
}
