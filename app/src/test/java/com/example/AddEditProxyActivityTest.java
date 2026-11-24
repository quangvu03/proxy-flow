package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.example.data.ProxyRepository;
import com.example.model.ProxyConfig;
import com.example.ui.AddEditProxyActivity;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.time.Duration;
import java.util.List;

/**
 * Robolectric tests verifying that adding and editing a proxy opens
 * the dedicated AddEditProxyActivity screen, parses clipboard entries,
 * validates inputs, and persists new proxies cleanly.
 */
@RunWith(RobolectricTestRunner.class)
public class AddEditProxyActivityTest {

    @Test
    public void clickAddProxyFab_launchesAddEditProxyActivity() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class)
                .setup()
                .get();

        BottomNavigationView bottomNav = activity.findViewById(R.id.bottom_navigation);
        assertNotNull(bottomNav);
        bottomNav.setSelectedItemId(R.id.nav_proxies);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        View fabAdd = activity.findViewById(R.id.fab_add_proxy);
        assertNotNull(fabAdd);
        fabAdd.performClick();

        Intent nextIntent = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(nextIntent);
        assertNotNull(nextIntent.getComponent());
        assertEquals(AddEditProxyActivity.class.getName(), nextIntent.getComponent().getClassName());
        assertEquals(-1L, nextIntent.getLongExtra(AddEditProxyActivity.EXTRA_PROXY_ID, -1L));
    }

    @Test
    public void addEditProxyActivity_validatesParsesClipboardAndSavesProxy() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        assertNotNull(cm);
        cm.setPrimaryClip(ClipData.newPlainText("proxy", "203.0.113.55:9090:alice:secret123"));

        AddEditProxyActivity activity = Robolectric.buildActivity(AddEditProxyActivity.class)
                .setup()
                .get();

        MaterialToolbar toolbar = activity.findViewById(R.id.add_edit_proxy_toolbar);
        assertNotNull(toolbar);
        assertEquals(activity.getString(R.string.title_add_proxy), toolbar.getTitle().toString());

        TextInputLayout tilHost = activity.findViewById(R.id.til_proxy_host);
        TextInputEditText etHost = activity.findViewById(R.id.et_proxy_host);
        TextInputEditText etPort = activity.findViewById(R.id.et_proxy_port);
        TextInputEditText etUser = activity.findViewById(R.id.et_proxy_user);
        TextInputEditText etPass = activity.findViewById(R.id.et_proxy_pass);
        TextInputEditText etName = activity.findViewById(R.id.et_proxy_name);
        View btnSave = activity.findViewById(R.id.btn_save_proxy);
        View btnPaste = activity.findViewById(R.id.btn_paste_parse);

        // Saving with empty host triggers validation error instead of closing
        btnSave.performClick();
        assertNotNull(tilHost.getError());
        assertTrue(!activity.isFinishing());

        // Clipboard auto-fill populates host, port, user, pass and clears error
        btnPaste.performClick();
        assertNull(tilHost.getError());
        assertEquals("203.0.113.55", etHost.getText().toString());
        assertEquals("9090", etPort.getText().toString());
        assertEquals("alice", etUser.getText().toString());
        assertEquals("secret123", etPass.getText().toString());

        etName.setText("VN Node 1");
        btnSave.performClick();

        // Wait briefly for repository background write to finish and post finish()
        for (int i = 0; i < 20 && !activity.isFinishing(); i++) {
            Thread.sleep(25);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25));
        }
        assertTrue(activity.isFinishing());

        List<ProxyConfig> saved = ProxyRepository.getInstance(context).getProxies().getValue();
        assertNotNull(saved);
        boolean found = false;
        for (ProxyConfig p : saved) {
            if ("203.0.113.55".equals(p.getHost()) && p.getPort() == 9090) {
                found = true;
                assertEquals("VN Node 1", p.getName());
                break;
            }
        }
        assertTrue(found);
    }

    @Test
    public void addEditProxyActivity_withProxyIdExtra_setsEditTitle() {
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), AddEditProxyActivity.class);
        intent.putExtra(AddEditProxyActivity.EXTRA_PROXY_ID, 9999L);

        AddEditProxyActivity activity = Robolectric.buildActivity(AddEditProxyActivity.class, intent)
                .create()
                .get();

        MaterialToolbar toolbar = activity.findViewById(R.id.add_edit_proxy_toolbar);
        assertNotNull(toolbar);
        assertEquals(activity.getString(R.string.title_edit_proxy), toolbar.getTitle().toString());
    }

    @Test
    public void addEditProxyActivity_serverAndPortAreSeparateVerticalRows() {
        AddEditProxyActivity activity = Robolectric.buildActivity(AddEditProxyActivity.class)
                .setup()
                .get();

        View tilHost = activity.findViewById(R.id.til_proxy_host);
        View tilPort = activity.findViewById(R.id.til_proxy_port);
        assertNotNull(tilHost);
        assertNotNull(tilPort);
        assertEquals(tilHost.getParent(), tilPort.getParent());

        android.view.ViewGroup parent = (android.view.ViewGroup) tilHost.getParent();
        assertTrue(parent instanceof android.widget.LinearLayout);
        assertEquals(android.widget.LinearLayout.VERTICAL, ((android.widget.LinearLayout) parent).getOrientation());

        int hostIndex = parent.indexOfChild(tilHost);
        int portIndex = parent.indexOfChild(tilPort);
        assertTrue(portIndex > hostIndex);
    }

    @Test
    public void addEditProxyActivity_authAndDnsDropdownsControlConditionalFieldsAndPersist() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        AddEditProxyActivity activity = Robolectric.buildActivity(AddEditProxyActivity.class)
                .setup()
                .get();

        android.widget.AutoCompleteTextView actvProtocol = activity.findViewById(R.id.actv_proxy_protocol);
        android.widget.AutoCompleteTextView actvAuth = activity.findViewById(R.id.actv_proxy_auth_method);
        android.widget.AutoCompleteTextView actvDns = activity.findViewById(R.id.actv_proxy_dns_mode);
        View layoutCredentials = activity.findViewById(R.id.layout_proxy_credentials);
        TextInputLayout tilUser = activity.findViewById(R.id.til_proxy_user);
        TextInputLayout tilCustomDns = activity.findViewById(R.id.til_proxy_custom_dns);
        TextInputEditText etName = activity.findViewById(R.id.et_proxy_name);
        TextInputEditText etHost = activity.findViewById(R.id.et_proxy_host);
        TextInputEditText etPort = activity.findViewById(R.id.et_proxy_port);
        TextInputEditText etCustomDns = activity.findViewById(R.id.et_proxy_custom_dns);
        View btnSave = activity.findViewById(R.id.btn_save_proxy);

        // Verify dropdown adapters expose all 5 protocols, 2 auth methods, 4 DNS modes
        assertEquals(5, actvProtocol.getAdapter().getCount());
        assertEquals(2, actvAuth.getAdapter().getCount());
        assertEquals(4, actvDns.getAdapter().getCount());

        // Initially Auth = NONE (credentials hidden) and DNS = PROXY (custom DNS hidden)
        assertEquals(View.GONE, layoutCredentials.getVisibility());
        assertEquals(View.GONE, tilCustomDns.getVisibility());

        // Switch Auth -> Username/Password shows credential fields & does NOT require username
        activity.applyAuthMethodSelection(ProxyConfig.AuthMethod.USER_PASS);
        assertEquals(View.VISIBLE, layoutCredentials.getVisibility());
        etHost.setText("198.51.100.88");
        etPort.setText("1080");
        assertNull(tilUser.getError());

        // Switch Auth -> None hides credentials and clears error
        activity.applyAuthMethodSelection(ProxyConfig.AuthMethod.NONE);
        assertEquals(View.GONE, layoutCredentials.getVisibility());
        assertNull(tilUser.getError());

        // Switch DNS -> Cloudflare / Google keep custom DNS hidden; Custom shows it
        activity.applyDnsModeSelection(ProxyConfig.DnsMode.CLOUDFLARE);
        assertEquals(View.GONE, tilCustomDns.getVisibility());
        activity.applyDnsModeSelection(ProxyConfig.DnsMode.GOOGLE);
        assertEquals(View.GONE, tilCustomDns.getVisibility());
        activity.applyDnsModeSelection(ProxyConfig.DnsMode.CUSTOM);
        assertEquals(View.VISIBLE, tilCustomDns.getVisibility());

        // Invalid custom DNS triggers validation error
        etCustomDns.setText("bad-dns");
        btnSave.performClick();
        assertNotNull(tilCustomDns.getError());

        // Valid custom DNS + SOCKS5 + TLS protocol saves completely
        activity.applyProtocolSelection(ProxyConfig.Protocol.SOCKS5_TLS);
        etName.setText("Tokyo TLS");
        etCustomDns.setText("9.9.9.9");
        btnSave.performClick();

        for (int i = 0; i < 20 && !activity.isFinishing(); i++) {
            Thread.sleep(25);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25));
        }
        assertTrue(activity.isFinishing());

        List<ProxyConfig> saved = ProxyRepository.getInstance(context).getProxies().getValue();
        assertNotNull(saved);
        boolean found = false;
        for (ProxyConfig p : saved) {
            if ("198.51.100.88".equals(p.getHost()) && p.getPort() == 1080) {
                found = true;
                assertEquals("Tokyo TLS", p.getName());
                assertEquals(ProxyConfig.Protocol.SOCKS5_TLS, p.getProtocol());
                assertEquals(ProxyConfig.AuthMethod.NONE, p.getAuthMethod());
                assertEquals(ProxyConfig.DnsMode.CUSTOM, p.getDnsMode());
                assertEquals("9.9.9.9", p.getCustomDns());
                break;
            }
        }
        assertTrue(found);
    }

    @Test
    public void addEditProxyActivity_emptyDefaultPort_portLimitWarning_andConditionalTestButton() {
        AddEditProxyActivity activity = Robolectric.buildActivity(AddEditProxyActivity.class)
                .setup()
                .get();

        TextInputLayout tilPort = activity.findViewById(R.id.til_proxy_port);
        TextInputEditText etHost = activity.findViewById(R.id.et_proxy_host);
        TextInputEditText etPort = activity.findViewById(R.id.et_proxy_port);
        View layoutTestConnection = activity.findViewById(R.id.layout_test_proxy_connection);
        View btnTestConnection = activity.findViewById(R.id.btn_test_proxy_connection);

        assertNotNull(tilPort);
        assertNotNull(layoutTestConnection);
        assertNotNull(btnTestConnection);

        // 1. Default port is NOT pre-filled with 8080
        assertEquals("", etPort.getText() != null ? etPort.getText().toString() : "");
        assertNull(tilPort.getError());

        // 2. Test Connection button is hidden while server info is incomplete
        assertEquals(View.GONE, layoutTestConnection.getVisibility());

        // Entering only host keeps Test Connection button hidden
        etHost.setText("203.0.113.10");
        assertEquals(View.GONE, layoutTestConnection.getVisibility());

        // 3. Typing an out-of-range port (> 65535 or 0) triggers immediate limit warning & keeps Test button hidden
        etPort.setText("70000");
        assertEquals(activity.getString(R.string.error_port_invalid), tilPort.getError());
        assertEquals(View.GONE, layoutTestConnection.getVisibility());

        etPort.setText("0");
        assertEquals(activity.getString(R.string.error_port_invalid), tilPort.getError());
        assertEquals(View.GONE, layoutTestConnection.getVisibility());

        // 4. Typing a valid port clears error and reveals Test Connection button below server info
        etPort.setText("1080");
        assertNull(tilPort.getError());
        assertEquals(View.VISIBLE, layoutTestConnection.getVisibility());

        // Clearing host hides Test Connection button again
        etHost.setText("");
        assertEquals(View.GONE, layoutTestConnection.getVisibility());
    }

    @Test
    public void addEditProxyActivity_portTimeoutErrorShownUnderPortFieldNotInTestCard() {
        AddEditProxyActivity activity = Robolectric.buildActivity(AddEditProxyActivity.class)
                .setup()
                .get();

        TextInputLayout tilPort = activity.findViewById(R.id.til_proxy_port);
        TextInputEditText etHost = activity.findViewById(R.id.et_proxy_host);
        TextInputEditText etPort = activity.findViewById(R.id.et_proxy_port);
        View cardStatus = activity.findViewById(R.id.card_test_connection_status);
        View btnSaveAndConnect = activity.findViewById(R.id.btn_save_and_connect_proxy);
        assertNotNull(btnSaveAndConnect);

        etHost.setText("203.0.113.10");
        etPort.setText("8080");

        // Port timeout error goes below Port field and hides Test Connect status card
        activity.applyTestFailureResult("Timeout connecting to the specified port (8080)");
        assertEquals("Timeout connecting to the specified port (8080)",
                tilPort.getError() != null ? tilPort.getError().toString() : null);
        assertEquals(View.GONE, cardStatus.getVisibility());

        // Editing host clears port timeout error while port remains valid
        etHost.setText("203.0.113.11");
        assertNull(tilPort.getError());

        // Non-timeout error displays inside Test Connect status card
        activity.applyTestFailureResult("Connection refused");
        assertNull(tilPort.getError());
        assertEquals(View.VISIBLE, cardStatus.getVisibility());
    }
}
