package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        TextInputLayout tilPass = activity.findViewById(R.id.til_proxy_pass);
        TextInputLayout tilToken = activity.findViewById(R.id.til_proxy_token);
        TextInputEditText etToken = activity.findViewById(R.id.et_proxy_token);
        TextInputLayout tilCustomDns = activity.findViewById(R.id.til_proxy_custom_dns);
        TextInputEditText etName = activity.findViewById(R.id.et_proxy_name);
        TextInputEditText etHost = activity.findViewById(R.id.et_proxy_host);
        TextInputEditText etPort = activity.findViewById(R.id.et_proxy_port);
        TextInputEditText etCustomDns = activity.findViewById(R.id.et_proxy_custom_dns);
        View btnSave = activity.findViewById(R.id.btn_save_proxy);

        // Verify dropdown adapters expose 6 protocols, 5 auth methods, 4 DNS modes
        // and default protocol when adding a new proxy is All Protocols
        assertEquals(ProxyConfig.Protocol.ALL.getDisplayLabel(activity), actvProtocol.getText().toString());
        assertEquals(6, actvProtocol.getAdapter().getCount());
        assertEquals(5, actvAuth.getAdapter().getCount());
        assertEquals(4, actvDns.getAdapter().getCount());

        // Initially Auth = NONE (credentials hidden) and DNS = PROXY (custom DNS hidden)
        assertEquals(View.GONE, layoutCredentials.getVisibility());
        assertEquals(View.GONE, tilCustomDns.getVisibility());

        // Switch Auth -> USER_PASS / HTTP_DIGEST shows user+pass, hides token, and requires credentials on save
        activity.applyAuthMethodSelection(ProxyConfig.AuthMethod.HTTP_DIGEST);
        assertEquals(View.VISIBLE, layoutCredentials.getVisibility());
        assertEquals(View.VISIBLE, tilUser.getVisibility());
        assertEquals(View.VISIBLE, tilPass.getVisibility());
        assertEquals(View.GONE, tilToken.getVisibility());
        etHost.setText("198.51.100.88");
        etPort.setText("1080");
        btnSave.performClick();
        assertNotNull(tilUser.getError());
        assertFalse(activity.isFinishing());

        // Switch Auth -> BEARER_TOKEN shows token field, hides user/pass, validates token on save
        activity.applyAuthMethodSelection(ProxyConfig.AuthMethod.BEARER_TOKEN);
        assertEquals(View.VISIBLE, layoutCredentials.getVisibility());
        assertEquals(View.GONE, tilUser.getVisibility());
        assertEquals(View.GONE, tilPass.getVisibility());
        assertEquals(View.VISIBLE, tilToken.getVisibility());
        btnSave.performClick();
        assertNotNull(tilToken.getError());
        assertFalse(activity.isFinishing());

        // Switch Auth -> None hides credentials and clears errors
        activity.applyAuthMethodSelection(ProxyConfig.AuthMethod.NONE);
        assertEquals(View.GONE, layoutCredentials.getVisibility());
        assertNull(tilUser.getError());
        assertNull(tilToken.getError());

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

        // Valid Bearer token + custom DNS + SOCKS5_TLS persists completely
        activity.applyProtocolSelection(ProxyConfig.Protocol.SOCKS5_TLS);
        activity.applyAuthMethodSelection(ProxyConfig.AuthMethod.BEARER_TOKEN);
        etName.setText("Tokyo TLS");
        etToken.setText("tok_secret_99");
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
                assertEquals(ProxyConfig.AuthMethod.BEARER_TOKEN, p.getAuthMethod());
                assertEquals("tok_secret_99", p.getAuthToken());
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

    @Test
    public void addEditProxyActivity_connectButtonDimmedUntilTestSucceeds_andAutoSelectsDetectedProtocol() {
        try (org.robolectric.android.controller.ActivityController<AddEditProxyActivity> controller =
                     Robolectric.buildActivity(AddEditProxyActivity.class).setup()) {
            AddEditProxyActivity activity = controller.get();
            android.widget.AutoCompleteTextView actvProtocol = activity.findViewById(R.id.actv_proxy_protocol);
            TextInputEditText etHost = activity.findViewById(R.id.et_proxy_host);
            TextInputEditText etPort = activity.findViewById(R.id.et_proxy_port);
            android.widget.TextView tvResult = activity.findViewById(R.id.tv_test_connection_result);
            View btnSaveAndConnect = activity.findViewById(R.id.btn_save_and_connect_proxy);

            assertNotNull(btnSaveAndConnect);
            assertFalse(btnSaveAndConnect.isEnabled());
            assertTrue(btnSaveAndConnect.getAlpha() < 0.6f);

            // Entering host & port alone leaves Connect disabled
            etHost.setText("203.0.113.10");
            etPort.setText("8080");
            assertFalse(btnSaveAndConnect.isEnabled());
            assertTrue(btnSaveAndConnect.getAlpha() < 0.6f);

            // Failed test keeps Connect disabled
            activity.applyTestFailureResult("Connection refused");
            assertFalse(btnSaveAndConnect.isEnabled());

            // Passing test lights up and enables Connect
            activity.applyTestSuccessResult(42L, "203.0.113.10", ProxyConfig.Protocol.HTTP);
            assertTrue(btnSaveAndConnect.isEnabled());
            assertEquals(1.0f, btnSaveAndConnect.getAlpha(), 0.01f);

            // Editing server field dims Connect again
            etHost.setText("203.0.113.11");
            assertFalse(btnSaveAndConnect.isEnabled());
            assertTrue(btnSaveAndConnect.getAlpha() < 0.6f);

            // Selecting All Protocols and getting a detected protocol updates dropdown & enables Connect
            activity.applyProtocolSelection(ProxyConfig.Protocol.ALL);
            assertFalse(btnSaveAndConnect.isEnabled());
            activity.applyTestSuccessResult(25L, "198.51.100.5", ProxyConfig.Protocol.SOCKS5);
            assertEquals("SOCKS5", actvProtocol.getText().toString());
            assertTrue(tvResult.getText().toString().contains("SOCKS5"));
            assertTrue(btnSaveAndConnect.isEnabled());
            assertEquals(1.0f, btnSaveAndConnect.getAlpha(), 0.01f);
        } finally {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }
}
