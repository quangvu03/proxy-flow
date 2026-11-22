package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.example.model.ProxyLogEntry;
import com.example.ui.LogListAdapter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import java.util.Arrays;

/**
 * Unit tests verifying the MainActivity startup splash overlay branding and
 * uniform fixed-width alignment of INFO/WARN/ERROR/SUCCESS badges in LogListAdapter.
 */
@RunWith(RobolectricTestRunner.class)
public class SplashAndLogAlignmentTest {

    @Test
    public void mainActivity_displaysSplashOverlayIconTitleAndTaglineOnLaunch() {
        MainActivity.resetStartupSplashForTests();
        try (ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            View overlay = activity.findViewById(R.id.splash_overlay);
            ImageView logo = activity.findViewById(R.id.iv_splash_logo);
            TextView title = activity.findViewById(R.id.tv_splash_title);
            TextView tagline = activity.findViewById(R.id.tv_splash_tagline);
            View progress = activity.findViewById(R.id.progress_splash);

            assertNotNull(overlay);
            assertNotNull(logo);
            assertNotNull(title);
            assertNotNull(tagline);
            assertNotNull(progress);

            assertEquals(View.VISIBLE, overlay.getVisibility());
            assertEquals("ProxyFlow", title.getText().toString());
            assertTrue(tagline.getText().length() > 10);
        }
    }

    @Test
    public void logListAdapter_levelAndTimeHaveUniformFixedWidthAcrossLevels() {
        Context context = ApplicationProvider.getApplicationContext();
        context.setTheme(R.style.Theme_ProxyFlow);

        LogListAdapter adapter = new LogListAdapter();
        adapter.submitList(Arrays.asList(
                new ProxyLogEntry(1L, 1_700_000_000_000L, ProxyLogEntry.Level.INFO, "Info message"),
                new ProxyLogEntry(2L, 1_700_000_001_000L, ProxyLogEntry.Level.SUCCESS, "Success message"),
                new ProxyLogEntry(3L, 1_700_000_002_000L, ProxyLogEntry.Level.WARN, "Warn message"),
                new ProxyLogEntry(4L, 1_700_000_003_000L, ProxyLogEntry.Level.ERROR, "Error message")
        ));

        FrameLayout parent = new FrameLayout(context);
        int expectedLevelWidth = -1;
        int expectedTimeWidth = -1;

        for (int i = 0; i < adapter.getItemCount(); i++) {
            LogListAdapter.LogViewHolder holder = adapter.onCreateViewHolder(parent, 0);
            adapter.onBindViewHolder(holder, i);
            TextView tvTime = holder.itemView.findViewById(R.id.tv_log_time);
            TextView tvLevel = holder.itemView.findViewById(R.id.tv_log_level);

            int levelWidth = tvLevel.getLayoutParams().width;
            int timeWidth = tvTime.getLayoutParams().width;
            assertTrue(levelWidth > 0);
            assertTrue(timeWidth > 0);

            if (expectedLevelWidth == -1) {
                expectedLevelWidth = levelWidth;
                expectedTimeWidth = timeWidth;
            } else {
                assertEquals(expectedLevelWidth, levelWidth);
                assertEquals(expectedTimeWidth, timeWidth);
            }
        }
    }
}
