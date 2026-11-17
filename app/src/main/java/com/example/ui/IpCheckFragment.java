package com.example.ui;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.R;
import com.example.model.IpDetails;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.io.File;
import java.util.Arrays;
import java.util.Locale;

/**
 * Fragment displaying Current Active IP vs Original Device IP (IPv4, IPv6,
 * Province/Region, City, Country, ISP, Coordinates) and an interactive OpenStreetMap
 * showing where the active IP and original IP are located.
 */
public class IpCheckFragment extends Fragment {

    private ProxyViewModel viewModel;

    private MapView mapView;
    private TextView tvMapOverlayLocation;
    private FloatingActionButton fabRefreshIp;
    private ChipGroup chipGroupFocus;
    private LinearProgressIndicator progressIpCheck;
    private TextView tvIpMaskStatus;
    private MaterialButton btnCheckIpNow;

    private TextView tvCurrentRouteBadge;
    private TextView tvCurrentIpv4;
    private TextView tvCurrentIpv6;
    private TextView tvCurrentRegion;
    private TextView tvCurrentCity;
    private TextView tvCurrentCountry;
    private TextView tvCurrentIsp;
    private TextView tvCurrentCoords;
    private ImageButton btnCopyCurrentIpv4;
    private ImageButton btnCopyCurrentIpv6;

    private TextView tvOriginIpv4;
    private TextView tvOriginIpv6;
    private TextView tvOriginRegion;
    private TextView tvOriginCity;
    private TextView tvOriginCountry;
    private TextView tvOriginIsp;
    private ImageButton btnCopyOriginIpv4;
    private ImageButton btnCopyOriginIpv6;

    private IpDetails.IpComparisonResult latestResult;
    private int focusMode = 0; // 0 = current, 1 = origin, 2 = both

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        initOsmdroidConfig(requireContext());
        return inflater.inflate(R.layout.fragment_ip_check, container, false);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(ProxyViewModel.class);

        mapView = view.findViewById(R.id.map_ip_location);
        tvMapOverlayLocation = view.findViewById(R.id.tv_map_overlay_location);
        fabRefreshIp = view.findViewById(R.id.fab_refresh_ip);
        chipGroupFocus = view.findViewById(R.id.chip_group_map_focus);
        progressIpCheck = view.findViewById(R.id.progress_ip_check);
        tvIpMaskStatus = view.findViewById(R.id.tv_ip_mask_status);
        btnCheckIpNow = view.findViewById(R.id.btn_check_ip_now);

        tvCurrentRouteBadge = view.findViewById(R.id.tv_current_ip_route_badge);
        tvCurrentIpv4 = view.findViewById(R.id.tv_current_ipv4);
        tvCurrentIpv6 = view.findViewById(R.id.tv_current_ipv6);
        tvCurrentRegion = view.findViewById(R.id.tv_current_region);
        tvCurrentCity = view.findViewById(R.id.tv_current_city);
        tvCurrentCountry = view.findViewById(R.id.tv_current_country);
        tvCurrentIsp = view.findViewById(R.id.tv_current_isp);
        tvCurrentCoords = view.findViewById(R.id.tv_current_coords);
        btnCopyCurrentIpv4 = view.findViewById(R.id.btn_copy_current_ipv4);
        btnCopyCurrentIpv6 = view.findViewById(R.id.btn_copy_current_ipv6);

        tvOriginIpv4 = view.findViewById(R.id.tv_origin_ipv4);
        tvOriginIpv6 = view.findViewById(R.id.tv_origin_ipv6);
        tvOriginRegion = view.findViewById(R.id.tv_origin_region);
        tvOriginCity = view.findViewById(R.id.tv_origin_city);
        tvOriginCountry = view.findViewById(R.id.tv_origin_country);
        tvOriginIsp = view.findViewById(R.id.tv_origin_isp);
        btnCopyOriginIpv4 = view.findViewById(R.id.btn_copy_origin_ipv4);
        btnCopyOriginIpv6 = view.findViewById(R.id.btn_copy_origin_ipv6);

        setupMapView();

        View.OnClickListener refreshClick = v -> viewModel.refreshIpComparison();
        fabRefreshIp.setOnClickListener(refreshClick);
        btnCheckIpNow.setOnClickListener(refreshClick);

        chipGroupFocus.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.contains(R.id.chip_focus_origin)) {
                focusMode = 1;
            } else if (checkedIds.contains(R.id.chip_focus_both)) {
                focusMode = 2;
            } else {
                focusMode = 0;
            }
            updateMapFocus();
        });

        btnCopyCurrentIpv4.setOnClickListener(v -> copyText(tvCurrentIpv4.getText().toString()));
        btnCopyCurrentIpv6.setOnClickListener(v -> copyText(tvCurrentIpv6.getText().toString()));
        btnCopyOriginIpv4.setOnClickListener(v -> copyText(tvOriginIpv4.getText().toString()));
        btnCopyOriginIpv6.setOnClickListener(v -> copyText(tvOriginIpv6.getText().toString()));

        viewModel.isCheckingIp().observe(getViewLifecycleOwner(), checking -> {
            boolean loading = checking != null && checking;
            progressIpCheck.setVisibility(loading ? View.VISIBLE : View.GONE);
            btnCheckIpNow.setEnabled(!loading);
            fabRefreshIp.setEnabled(!loading);
            if (loading && latestResult == null) {
                tvIpMaskStatus.setText(R.string.label_checking_progress);
            }
        });

        viewModel.getIpComparison().observe(getViewLifecycleOwner(), result -> {
            if (result != null) {
                latestResult = result;
                renderResult(result);
            }
        });

        if (viewModel.getIpComparison().getValue() == null) {
            viewModel.refreshIpComparison();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupMapView() {
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(5.0);
        // Default initial center (Vietnam / Southeast Asia) until coordinates load
        mapView.getController().setCenter(new GeoPoint(16.0544, 108.2022));

        // Allow panning/zooming the map smoothly inside NestedScrollView
        mapView.setOnTouchListener((v, event) -> {
            int action = event.getAction();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });
    }

    private void renderResult(IpDetails.IpComparisonResult result) {
        if (!isAdded()) return;
        IpDetails current = result.getCurrentIp();
        IpDetails origin = result.getOriginIp();

        if (result.isMasked()) {
            tvIpMaskStatus.setText(R.string.badge_ip_masked);
            tvIpMaskStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_connected));
            tvIpMaskStatus.setBackgroundTintList(
                    ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_connected_bg)));
        } else if (current.isRoutedViaProxy() && !current.getError().isEmpty()) {
            tvIpMaskStatus.setText(getString(R.string.msg_ip_check_failed, current.getError()));
            tvIpMaskStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_error));
            tvIpMaskStatus.setBackgroundTintList(
                    ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_error_bg)));
        } else {
            tvIpMaskStatus.setText(R.string.badge_ip_exposed);
            tvIpMaskStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_switching));
            tvIpMaskStatus.setBackgroundTintList(
                    ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_switching_bg)));
        }

        if (current.isRoutedViaProxy()) {
            tvCurrentRouteBadge.setText(R.string.badge_proxy_routed);
            tvCurrentRouteBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_connected));
            tvCurrentRouteBadge.setBackgroundTintList(
                    ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_connected_bg)));
        } else {
            tvCurrentRouteBadge.setText(R.string.badge_direct_network);
            tvCurrentRouteBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_idle));
            tvCurrentRouteBadge.setBackgroundTintList(
                    ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_idle_bg)));
        }

        tvCurrentIpv4.setText(nonEmptyOrFallback(current.getIpv4(), getString(R.string.ip_not_available)));
        tvCurrentIpv6.setText(nonEmptyOrFallback(current.getIpv6(), getString(R.string.ip_not_available)));
        tvCurrentRegion.setText(nonEmptyOrFallback(current.getRegion(), "—"));
        tvCurrentCity.setText(nonEmptyOrFallback(current.getCity(), "—"));
        tvCurrentCountry.setText(formatCountry(current));
        tvCurrentIsp.setText(nonEmptyOrFallback(current.getIsp(), "—"));
        tvCurrentCoords.setText(current.hasCoordinates()
                ? String.format(Locale.US, "%.4f, %.4f", current.getLatitude(), current.getLongitude())
                : "—");

        tvOriginIpv4.setText(nonEmptyOrFallback(origin.getIpv4(), getString(R.string.ip_not_available)));
        tvOriginIpv6.setText(nonEmptyOrFallback(origin.getIpv6(), getString(R.string.ip_not_available)));
        tvOriginRegion.setText(nonEmptyOrFallback(origin.getRegion(), "—"));
        tvOriginCity.setText(nonEmptyOrFallback(origin.getCity(), "—"));
        tvOriginCountry.setText(formatCountry(origin));
        tvOriginIsp.setText(nonEmptyOrFallback(origin.getIsp(), "—"));

        tvMapOverlayLocation.setText(current.hasCoordinates()
                ? current.getLocationSummary()
                : origin.getLocationSummary());

        renderMapMarkers(current, origin);
    }

    private void renderMapMarkers(IpDetails current, IpDetails origin) {
        mapView.getOverlays().clear();

        GeoPoint currentPoint = current.hasCoordinates()
                ? new GeoPoint(current.getLatitude(), current.getLongitude())
                : null;
        GeoPoint originPoint = origin.hasCoordinates()
                ? new GeoPoint(origin.getLatitude(), origin.getLongitude())
                : null;

        if (currentPoint != null && originPoint != null
                && (Math.abs(currentPoint.getLatitude() - originPoint.getLatitude()) > 0.01
                || Math.abs(currentPoint.getLongitude() - originPoint.getLongitude()) > 0.01)) {
            Polyline line = new Polyline(mapView);
            line.setPoints(Arrays.asList(originPoint, currentPoint));
            line.getOutlinePaint().setColor(Color.parseColor("#0D9488"));
            line.getOutlinePaint().setStrokeWidth(6f);
            mapView.getOverlays().add(line);
        }

        if (originPoint != null) {
            Marker originMarker = new Marker(mapView);
            originMarker.setPosition(originPoint);
            originMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            originMarker.setIcon(ContextCompat.getDrawable(requireContext(), R.drawable.ic_map_marker_origin));
            originMarker.setTitle(getString(R.string.label_focus_origin_ip) + ": " + origin.getIpv4());
            originMarker.setSnippet(origin.getLocationSummary());
            mapView.getOverlays().add(originMarker);
        }

        if (currentPoint != null) {
            Marker currentMarker = new Marker(mapView);
            currentMarker.setPosition(currentPoint);
            currentMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            currentMarker.setIcon(ContextCompat.getDrawable(requireContext(), R.drawable.ic_map_marker_proxy));
            currentMarker.setTitle(getString(R.string.label_focus_current_ip) + ": " + current.getIpv4());
            currentMarker.setSnippet(current.getLocationSummary());
            mapView.getOverlays().add(currentMarker);
            currentMarker.showInfoWindow();
        }

        updateMapFocus();
        mapView.invalidate();
    }

    private void updateMapFocus() {
        if (latestResult == null || mapView == null) return;
        IpDetails current = latestResult.getCurrentIp();
        IpDetails origin = latestResult.getOriginIp();

        GeoPoint currentPoint = current.hasCoordinates()
                ? new GeoPoint(current.getLatitude(), current.getLongitude())
                : null;
        GeoPoint originPoint = origin.hasCoordinates()
                ? new GeoPoint(origin.getLatitude(), origin.getLongitude())
                : null;

        if (focusMode == 1 && originPoint != null) {
            mapView.getController().setZoom(11.0);
            mapView.getController().animateTo(originPoint);
            tvMapOverlayLocation.setText(getString(R.string.label_focus_origin_ip) + ": " + origin.getLocationSummary());
        } else if (focusMode == 2 && currentPoint != null && originPoint != null) {
            double north = Math.max(currentPoint.getLatitude(), originPoint.getLatitude()) + 2.0;
            double south = Math.min(currentPoint.getLatitude(), originPoint.getLatitude()) - 2.0;
            double east = Math.max(currentPoint.getLongitude(), originPoint.getLongitude()) + 2.0;
            double west = Math.min(currentPoint.getLongitude(), originPoint.getLongitude()) - 2.0;
            BoundingBox box = new BoundingBox(
                    Math.min(85.0, north),
                    Math.min(180.0, east),
                    Math.max(-85.0, south),
                    Math.max(-180.0, west)
            );
            mapView.post(() -> mapView.zoomToBoundingBox(box, true, 64));
            tvMapOverlayLocation.setText(origin.getCountry() + " → " + current.getCountry());
        } else if (currentPoint != null) {
            mapView.getController().setZoom(11.0);
            mapView.getController().animateTo(currentPoint);
            tvMapOverlayLocation.setText(current.getLocationSummary());
        } else if (originPoint != null) {
            mapView.getController().setZoom(11.0);
            mapView.getController().animateTo(originPoint);
            tvMapOverlayLocation.setText(origin.getLocationSummary());
        }
    }

    private void copyText(String value) {
        if (value == null || value.isEmpty() || "—".equals(value)
                || value.equals(getString(R.string.ip_not_available))) {
            return;
        }
        ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("IP Address", value));
            Toast.makeText(requireContext(), R.string.msg_ip_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private static String nonEmptyOrFallback(String value, String fallback) {
        return (value != null && !value.trim().isEmpty()) ? value.trim() : fallback;
    }

    private static String formatCountry(IpDetails details) {
        if (details.getCountry().isEmpty()) return "—";
        if (details.getCountryCode().isEmpty()) return details.getCountry();
        return details.getCountry() + " (" + details.getCountryCode() + ")";
    }

    private static void initOsmdroidConfig(Context context) {
        Context appCtx = context.getApplicationContext();
        File osmBase = new File(appCtx.getCacheDir(), "osmdroid");
        File osmTiles = new File(osmBase, "tiles");
        if (!osmTiles.exists()) {
            //noinspection ResultOfMethodCallIgnored
            osmTiles.mkdirs();
        }
        Configuration.getInstance().setUserAgentValue(appCtx.getPackageName());
        Configuration.getInstance().setOsmdroidBasePath(osmBase);
        Configuration.getInstance().setOsmdroidTileCache(osmTiles);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mapView != null) {
            mapView.onResume();
        }
    }

    @Override
    public void onPause() {
        if (mapView != null) {
            mapView.onPause();
        }
        super.onPause();
    }
}
