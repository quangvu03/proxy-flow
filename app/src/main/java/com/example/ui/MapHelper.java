package com.example.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.Polyline;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pure Java map helper configured for smooth 60fps zooming, continuous horizontal world wrap-around
 * (no black void at edges), deep zoom (up to level 20), and a clean Google Maps-like aesthetic:
 * - Standard 256x256 PNG tiles (100% native osmdroid BitmapPool compatibility)
 * - Primary Light: CARTO Voyager (Google Maps pastel palette)
 * - Primary Dark: CARTO Dark Matter
 * - Automatic fallback to Esri World Street Map and OpenStreetMap Standard
 * - Horizontal wrap-around + clamped polar latitude limits + ocean background fill (zero black edges)
 * - Smooth animated +/- zoom buttons with rapid-tap accumulation, double-tap zoom, and pinch-to-zoom
 * - Permanent Vietnam sovereignty overlay for Quần đảo Hoàng Sa & Quần đảo Trường Sa (Việt Nam)
 */
public final class MapHelper {

    private static final String USER_AGENT =
            "ProxyFlowAndroid/1.6 (https://topproxy.vn)";
    private static final String PREF_CACHE_SCHEMA = "tile_cache_schema_version";
    private static final int CURRENT_CACHE_SCHEMA = 3;

    public static final double DEFAULT_OVERVIEW_ZOOM = 5.5;
    public static final double IP_LOCATION_FOCUS_ZOOM = 7.5;

    private static final double MIN_ZOOM = 2.5;
    private static final double MAX_ZOOM = 20.0;
    private static final long ZOOM_ANIM_DURATION_MS = 260L;
    private static final long FOCUS_ANIM_DURATION_MS = 320L;

    private static final int SOURCE_CARTO_VOYAGER = 0;
    private static final int SOURCE_ESRI_STREET = 1;
    private static final int SOURCE_OSM = 2;

    // Standard 256x256 PNG tiles: matches Google Maps pastel palette and works flawlessly in osmdroid
    private static final OnlineTileSourceBase CARTO_VOYAGER_LIGHT = new XYTileSource(
            "CartoVoyagerStdV3",
            0,
            19,
            256,
            ".png",
            new String[]{
                    "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
                    "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
                    "https://c.basemaps.cartocdn.com/rastertiles/voyager/",
                    "https://d.basemaps.cartocdn.com/rastertiles/voyager/"
            },
            "© OpenStreetMap © CARTO"
    );

    private static final OnlineTileSourceBase CARTO_DARK_MATTER = new XYTileSource(
            "CartoDarkStdV3",
            0,
            19,
            256,
            ".png",
            new String[]{
                    "https://a.basemaps.cartocdn.com/dark_all/",
                    "https://b.basemaps.cartocdn.com/dark_all/",
                    "https://c.basemaps.cartocdn.com/dark_all/",
                    "https://d.basemaps.cartocdn.com/dark_all/"
            },
            "© OpenStreetMap © CARTO"
    );

    private static final OnlineTileSourceBase ESRI_WORLD_STREET = new OnlineTileSourceBase(
            "EsriStreetStdV3",
            0,
            19,
            256,
            "",
            new String[]{
                    "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/"
            },
            "© Esri"
    ) {
        @Override
        public String getTileURLString(long pMapTileIndex) {
            return getBaseUrl()
                    + MapTileIndex.getZoom(pMapTileIndex)
                    + "/" + MapTileIndex.getY(pMapTileIndex)
                    + "/" + MapTileIndex.getX(pMapTileIndex);
        }
    };

    private static final OnlineTileSourceBase OSM_STANDARD = new XYTileSource(
            "OSMStandardV3",
            0,
            19,
            256,
            ".png",
            new String[]{
                    "https://tile.openstreetmap.org/"
            },
            "© OpenStreetMap contributors"
    );

    private static final ExecutorService PROBE_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<MapView, Runnable> PENDING_FOCUS_ACTIONS = new WeakHashMap<>();
    private static final WeakHashMap<MapView, ZoomState> ZOOM_STATES = new WeakHashMap<>();

    private static final class ZoomState {
        double targetZoom;
        long expiresAtMs;
    }

    private static boolean initialized = false;
    private static volatile int activeSourceIndex = SOURCE_CARTO_VOYAGER;
    private static volatile boolean probeCompleted = false;

    private MapHelper() {}

    public static synchronized void initConfig(Context context) {
        if (initialized) return;
        Context appCtx = context.getApplicationContext();
        SharedPreferences prefs = appCtx.getSharedPreferences("osmdroid_prefs", Context.MODE_PRIVATE);
        IConfigurationProvider osmConfig = org.osmdroid.config.Configuration.getInstance();
        osmConfig.load(appCtx, prefs);

        File osmBase = new File(appCtx.getFilesDir(), "osmdroid");
        File osmTiles = new File(osmBase, "tiles");

        // Wipe legacy @2x tile cache once if upgrading from an older schema
        if (prefs.getInt(PREF_CACHE_SCHEMA, 0) < CURRENT_CACHE_SCHEMA) {
            deleteRecursively(osmTiles);
            prefs.edit().putInt(PREF_CACHE_SCHEMA, CURRENT_CACHE_SCHEMA).apply();
        }
        if (!osmTiles.exists()) {
            //noinspection ResultOfMethodCallIgnored
            osmTiles.mkdirs();
        }

        osmConfig.setUserAgentValue(USER_AGENT);
        osmConfig.setOsmdroidBasePath(osmBase);
        osmConfig.setOsmdroidTileCache(osmTiles);
        // Generous RAM tile cache + fast parallel downloads so zooming & panning stay silky smooth
        osmConfig.setCacheMapTileCount((short) 48);
        osmConfig.setCacheMapTileOvershoot((short) 48);
        osmConfig.setTileDownloadThreads((short) 6);
        osmConfig.setTileFileSystemThreads((short) 4);
        osmConfig.setTileDownloadMaxQueueSize((short) 60);
        osmConfig.setTileFileSystemMaxQueueSize((short) 60);
        // Snappy Google Maps-like zoom & pan animation timing
        osmConfig.setAnimationSpeedDefault((int) ZOOM_ANIM_DURATION_MS);
        osmConfig.setAnimationSpeedShort(200);

        Map<String, String> headers = osmConfig.getAdditionalHttpRequestProperties();
        if (headers != null) {
            headers.put("Accept", "image/png,image/*;q=0.8");
            headers.put("Accept-Language", "vi,en-US;q=0.9,en;q=0.8");
        }
        osmConfig.save(appCtx, prefs);
        initialized = true;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    @SuppressLint("ClickableViewAccessibility")
    public static void configureMapView(Context context, MapView mapView, double defaultZoom) {
        initConfig(context);

        boolean isDark = isNightMode(context);
        applyTileSource(mapView, activeSourceIndex, isDark);

        mapView.setUseDataConnection(true);
        mapView.setTilesScaledToDpi(true);
        mapView.setMultiTouchControls(true);
        mapView.setFlingEnabled(true);
        mapView.setZoomRounding(false);

        // Seamless horizontal globe wrap-around (like Google Maps) & strict polar clamping (no black void)
        mapView.setHorizontalMapRepetitionEnabled(true);
        mapView.setVerticalMapRepetitionEnabled(false);
        mapView.setScrollableAreaLimitLatitude(
                MapView.getTileSystem().getMaxLatitude(),
                MapView.getTileSystem().getMinLatitude(),
                0
        );
        mapView.setMinZoomLevel(MIN_ZOOM);
        mapView.setMaxZoomLevel(MAX_ZOOM);

        // Match ocean canvas color so there is never a black border or flash while zooming/panning
        int oceanBg = isDark ? Color.parseColor("#18202C") : Color.parseColor("#AAD3DF");
        mapView.setBackgroundColor(oceanBg);

        // Hide legacy osmdroid built-in zoom overlay; use crisp Google Maps-style +/- buttons
        mapView.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);

        if (mapView.getOverlayManager() != null && mapView.getOverlayManager().getTilesOverlay() != null) {
            int loadingBg = isDark ? Color.parseColor("#1B2432") : Color.parseColor("#DDE8F0");
            mapView.getOverlayManager().getTilesOverlay().setLoadingBackgroundColor(loadingBg);
            mapView.getOverlayManager().getTilesOverlay().setLoadingLineColor(Color.TRANSPARENT);
            mapView.getOverlayManager().getTilesOverlay().setColorFilter(null);
        }

        // Permanently show Vietnam Hoàng Sa & Trường Sa archipelagos
        addVietnamSovereigntyOverlay(context, mapView);

        // Default view framing Vietnam mainland + Hoàng Sa + Trường Sa
        focusOnPoint(mapView, new GeoPoint(14.20, 109.80), defaultZoom);

        // Properly allow 1-finger pan and 2-finger pinch zoom inside NestedScrollView
        mapView.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                ZOOM_STATES.remove(mapView);
                if (v.getParent() != null) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                }
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (v.getParent() != null) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (v.getParent() != null) {
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                }
            }
            return false;
        });

        if (!probeCompleted) {
            probeAndSwitchIfNeeded(context.getApplicationContext(), mapView);
        }
    }

    /**
     * Adds the Vietnam sovereignty overlay displaying Quần đảo Hoàng Sa (Việt Nam),
     * Quần đảo Trường Sa (Việt Nam), their key islands, and Biển Đông cleanly on the map.
     */
    public static void addVietnamSovereigntyOverlay(Context context, MapView mapView) {
        if (context == null || mapView == null) return;
        float density = context.getResources().getDisplayMetrics().density;
        boolean isDark = isNightMode(context);
        mapView.getOverlays().add(new VietnamIslandsOverlay(density, isDark));
    }

    /**
     * Draws a non-interactive Google Maps-style precision halo around an exact coordinate
     * without intercepting touches or washing out street details at high zoom levels.
     */
    public static void addPrecisionHalo(MapView mapView, GeoPoint center, double radiusMeters,
                                        int fillArgb, int strokeArgb) {
        if (mapView == null || center == null) return;
        mapView.getOverlays().add(new PrecisionHaloOverlay(center, radiusMeters, fillArgb, strokeArgb));
    }

    /**
     * Draws a crisp Google Maps-style blue route line with a subtle white border between two points.
     */
    public static void addGoogleStyleRoute(MapView mapView, GeoPoint start, GeoPoint end) {
        if (mapView == null || start == null || end == null) return;
        Polyline border = new Polyline(mapView);
        border.setPoints(Arrays.asList(start, end));
        border.getOutlinePaint().setColor(Color.argb(220, 255, 255, 255));
        border.getOutlinePaint().setStrokeWidth(9f);
        border.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        border.setInfoWindow(null);
        mapView.getOverlays().add(border);

        Polyline route = new Polyline(mapView);
        route.setPoints(Arrays.asList(start, end));
        route.getOutlinePaint().setColor(Color.parseColor("#1A73E8"));
        route.getOutlinePaint().setStrokeWidth(5f);
        route.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        route.setInfoWindow(null);
        mapView.getOverlays().add(route);
    }

    /**
     * Connects two dedicated Zoom In (+) and Zoom Out (-) buttons with smooth animated zoom
     * and rapid-tap accumulation so repeated taps glide continuously without snapping or stalling.
     */
    public static void attachZoomControls(MapView mapView, View btnZoomIn, View btnZoomOut) {
        if (mapView == null) return;
        if (btnZoomIn != null) {
            btnZoomIn.setOnClickListener(v -> smoothStepZoom(mapView, true));
        }
        if (btnZoomOut != null) {
            btnZoomOut.setOnClickListener(v -> smoothStepZoom(mapView, false));
        }
    }

    private static void smoothStepZoom(MapView mapView, boolean zoomIn) {
        long now = SystemClock.uptimeMillis();
        ZoomState state = ZOOM_STATES.get(mapView);
        if (state == null) {
            state = new ZoomState();
            ZOOM_STATES.put(mapView, state);
        }

        double baseZoom;
        if (now < state.expiresAtMs && mapView.isAnimating()) {
            baseZoom = state.targetZoom;
        } else {
            baseZoom = mapView.getZoomLevelDouble();
        }

        double targetZoom = zoomIn
                ? Math.min(MAX_ZOOM, Math.floor(baseZoom + 0.05) + 1.0)
                : Math.max(MIN_ZOOM, Math.ceil(baseZoom - 0.05) - 1.0);

        state.targetZoom = targetZoom;
        state.expiresAtMs = now + ZOOM_ANIM_DURATION_MS + 60L;

        mapView.getController().stopAnimation(false);
        mapView.getController().zoomTo(targetZoom, ZOOM_ANIM_DURATION_MS);
    }

    private static void applyTileSource(MapView mapView, int sourceIndex, boolean isDark) {
        OnlineTileSourceBase tileSource;
        switch (sourceIndex) {
            case SOURCE_ESRI_STREET:
                tileSource = ESRI_WORLD_STREET;
                break;
            case SOURCE_OSM:
                tileSource = OSM_STANDARD;
                break;
            case SOURCE_CARTO_VOYAGER:
            default:
                tileSource = isDark ? CARTO_DARK_MATTER : CARTO_VOYAGER_LIGHT;
                break;
        }
        mapView.setTileSource(tileSource);
    }

    private static void probeAndSwitchIfNeeded(Context appCtx, MapView mapView) {
        final int initialIndex = activeSourceIndex;
        final boolean isDark = isNightMode(appCtx);
        PROBE_EXECUTOR.execute(() -> {
            int[] order = new int[]{initialIndex, SOURCE_CARTO_VOYAGER, SOURCE_ESRI_STREET, SOURCE_OSM};
            int working = -1;
            for (int candidate : order) {
                if (isTileServerReachable(candidate, isDark)) {
                    working = candidate;
                    break;
                }
            }
            probeCompleted = true;
            if (working >= 0 && working != activeSourceIndex) {
                final int chosen = working;
                activeSourceIndex = chosen;
                MAIN_HANDLER.post(() -> {
                    if (mapView.isAttachedToWindow()) {
                        applyTileSource(mapView, chosen, isDark);
                        if (mapView.getTileProvider() != null) {
                            mapView.getTileProvider().clearTileCache();
                        }
                        mapView.invalidate();
                    }
                });
            }
        });
    }

    private static boolean isTileServerReachable(int sourceIndex, boolean isDark) {
        String probeUrl;
        switch (sourceIndex) {
            case SOURCE_ESRI_STREET:
                probeUrl = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/10/468/819";
                break;
            case SOURCE_OSM:
                probeUrl = "https://tile.openstreetmap.org/10/819/468.png";
                break;
            case SOURCE_CARTO_VOYAGER:
            default:
                probeUrl = isDark
                        ? "https://a.basemaps.cartocdn.com/dark_all/10/819/468.png"
                        : "https://a.basemaps.cartocdn.com/rastertiles/voyager/10/819/468.png";
                break;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(probeUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3500);
            conn.setReadTimeout(3500);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "image/png,image/*;q=0.8");
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static void focusOnPoint(MapView mapView, GeoPoint point, double zoom) {
        if (mapView == null || point == null) return;
        double clampedZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
        boolean alreadyLaidOut = mapView.isLayoutOccurred() && mapView.getWidth() > 0 && mapView.getHeight() > 0;
        Runnable action = () -> {
            ZOOM_STATES.remove(mapView);
            mapView.getController().stopAnimation(false);
            if (alreadyLaidOut) {
                mapView.getController().animateTo(point, clampedZoom, FOCUS_ANIM_DURATION_MS);
            } else {
                mapView.getController().setZoom(clampedZoom);
                mapView.getController().setCenter(point);
                mapView.invalidate();
            }
        };
        runWhenLaidOut(mapView, action);
    }

    public static void focusOnBounds(MapView mapView, BoundingBox box, int paddingPx) {
        if (mapView == null || box == null) return;
        boolean alreadyLaidOut = mapView.isLayoutOccurred() && mapView.getWidth() > 0 && mapView.getHeight() > 0;
        Runnable action = () -> {
            if (mapView.getWidth() > 0 && mapView.getHeight() > 0) {
                ZOOM_STATES.remove(mapView);
                mapView.getController().stopAnimation(false);
                mapView.zoomToBoundingBox(box, alreadyLaidOut, paddingPx);
                mapView.invalidate();
            }
        };
        runWhenLaidOut(mapView, action);
    }

    private static void runWhenLaidOut(MapView mapView, Runnable action) {
        if (mapView.isLayoutOccurred() && mapView.getWidth() > 0 && mapView.getHeight() > 0) {
            PENDING_FOCUS_ACTIONS.remove(mapView);
            action.run();
            return;
        }
        boolean alreadyRegistered = PENDING_FOCUS_ACTIONS.containsKey(mapView);
        PENDING_FOCUS_ACTIONS.put(mapView, action);
        if (!alreadyRegistered) {
            mapView.addOnFirstLayoutListener((v, left, top, right, bottom) -> {
                Runnable latest = PENDING_FOCUS_ACTIONS.remove(mapView);
                if (latest != null) {
                    latest.run();
                }
            });
        }
    }

    public static boolean isNightMode(Context context) {
        int uiMode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return uiMode == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Lightweight non-interactive circular accuracy halo drawn directly on the canvas.
     * Scales smoothly with map projection and remains subtle at street-level zooms.
     */
    private static final class PrecisionHaloOverlay extends Overlay {
        private final GeoPoint center;
        private final double radiusMeters;
        private final Paint fillPaint;
        private final Paint strokePaint;
        private final Point screenCenter = new Point();

        PrecisionHaloOverlay(GeoPoint center, double radiusMeters, int fillArgb, int strokeArgb) {
            this.center = center;
            this.radiusMeters = radiusMeters;

            fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(fillArgb);

            strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(2.2f);
            strokePaint.setColor(strokeArgb);
        }

        @Override
        public void draw(Canvas canvas, Projection proj) {
            if (canvas == null || proj == null) return;
            proj.toPixels(center, screenCenter);

            Rect rect = proj.getIntrinsicScreenRect();
            int viewMin = rect != null ? Math.min(rect.width(), rect.height()) : 300;

            float radiusPx = proj.metersToPixels((float) radiusMeters, center.getLatitude(), proj.getZoomLevel());
            // Clamp visual radius so it stays crisp when zoomed out and never washes out the screen at deep zoom
            float minPx = 14f;
            float maxPx = viewMin * 0.28f;
            if (maxPx < minPx) maxPx = minPx;
            float clamped = Math.max(minPx, Math.min(maxPx, radiusPx));

            canvas.drawCircle(screenCenter.x, screenCenter.y, clamped, fillPaint);
            canvas.drawCircle(screenCenter.x, screenCenter.y, clamped, strokePaint);
        }
    }

    /**
     * Native Google Maps-style cartographic overlay displaying Vietnam's Hoàng Sa and Trường Sa
     * archipelagos (and Biển Đông) at exact verified geographic coordinates using crisp halo labels.
     */
    private static final class VietnamIslandsOverlay extends Overlay {

        // Exact geographic centers of both Vietnamese archipelagos
        private static final GeoPoint HOANG_SA_CENTER = new GeoPoint(16.5500, 111.9500);
        private static final GeoPoint TRUONG_SA_CENTER = new GeoPoint(9.5500, 113.3500);
        private static final GeoPoint BIEN_DONG_CENTER = new GeoPoint(13.2000, 113.0000);

        // Verified coordinates for Quần đảo Hoàng Sa (TP. Đà Nẵng • Việt Nam)
        private static final GeoPoint[] HOANG_SA_ISLANDS = new GeoPoint[]{
                new GeoPoint(16.8353, 112.3381), // Đảo Phú Lâm (16°50'07"N 112°20'17"E)
                new GeoPoint(16.5347, 111.6083), // Đảo Hoàng Sa (16°32'05"N 111°36'30"E)
                new GeoPoint(16.5064, 111.5867), // Đảo Hữu Nhật (16°30'23"N 111°35'12"E)
                new GeoPoint(16.4494, 111.7111), // Đảo Quang Hòa (16°26'58"N 111°42'40"E)
                new GeoPoint(15.7833, 111.2044), // Đảo Tri Tôn (15°47'00"N 111°12'16"E)
                new GeoPoint(16.6667, 112.7333)  // Đảo Linh Côn (16°40'00"N 112°44'00"E)
        };

        private static final String[] HOANG_SA_NAMES = new String[]{
                "Đảo Phú Lâm",
                "Đảo Hoàng Sa",
                "Đảo Hữu Nhật",
                "Đảo Quang Hòa",
                "Đảo Tri Tôn",
                "Đảo Linh Côn"
        };

        // Verified coordinates for Quần đảo Trường Sa (T. Khánh Hòa • Việt Nam)
        private static final GeoPoint[] TRUONG_SA_ISLANDS = new GeoPoint[]{
                new GeoPoint(8.6441, 111.9194),  // Đảo Trường Sa Lớn (8°38'39"N 111°55'10"E)
                new GeoPoint(11.4294, 114.3314), // Đảo Song Tử Tây (11°25'46"N 114°19'53"E)
                new GeoPoint(9.8853, 114.3297),  // Đảo Sinh Tồn (9°53'07"N 114°19'47"E)
                new GeoPoint(10.1792, 114.3650), // Đảo Nam Yết (10°10'45"N 114°21'54"E)
                new GeoPoint(10.3750, 114.4800), // Đảo Sơn Ca (10°22'30"N 114°28'48"E)
                new GeoPoint(7.8900, 112.9214)   // Đảo An Bang (7°53'24"N 112°55'17"E)
        };

        private static final String[] TRUONG_SA_NAMES = new String[]{
                "Đảo Trường Sa Lớn",
                "Đảo Song Tử Tây",
                "Đảo Sinh Tồn",
                "Đảo Nam Yết",
                "Đảo Sơn Ca",
                "Đảo An Bang"
        };

        private final float density;
        private final Paint titleFillPaint;
        private final Paint titleHaloPaint;
        private final Paint subTitleFillPaint;
        private final Paint subTitleHaloPaint;
        private final Paint seaPaint;
        private final Paint islandDotPaint;
        private final Paint islandDotRingPaint;
        private final Paint islandLabelFillPaint;
        private final Paint islandLabelHaloPaint;
        private final Point screenPt = new Point();

        VietnamIslandsOverlay(float density, boolean isDark) {
            this.density = density;
            int haloColor = isDark ? Color.argb(230, 20, 30, 46) : Color.argb(240, 255, 255, 255);

            Typeface boldSans = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
            Typeface normalSans = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

            titleHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            titleHaloPaint.setStyle(Paint.Style.STROKE);
            titleHaloPaint.setStrokeJoin(Paint.Join.ROUND);
            titleHaloPaint.setStrokeWidth(3.2f * density);
            titleHaloPaint.setColor(haloColor);
            titleHaloPaint.setTextSize(10.5f * density);
            titleHaloPaint.setTextAlign(Paint.Align.CENTER);
            titleHaloPaint.setTypeface(boldSans);

            titleFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            titleFillPaint.setStyle(Paint.Style.FILL);
            titleFillPaint.setColor(isDark ? Color.parseColor("#E8EAED") : Color.parseColor("#202124"));
            titleFillPaint.setTextSize(10.5f * density);
            titleFillPaint.setTextAlign(Paint.Align.CENTER);
            titleFillPaint.setTypeface(boldSans);

            subTitleHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            subTitleHaloPaint.setStyle(Paint.Style.STROKE);
            subTitleHaloPaint.setStrokeJoin(Paint.Join.ROUND);
            subTitleHaloPaint.setStrokeWidth(2.8f * density);
            subTitleHaloPaint.setColor(haloColor);
            subTitleHaloPaint.setTextSize(8.8f * density);
            subTitleHaloPaint.setTextAlign(Paint.Align.CENTER);
            subTitleHaloPaint.setTypeface(normalSans);

            subTitleFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            subTitleFillPaint.setStyle(Paint.Style.FILL);
            subTitleFillPaint.setColor(isDark ? Color.parseColor("#8AB4F8") : Color.parseColor("#1A73E8"));
            subTitleFillPaint.setTextSize(8.8f * density);
            subTitleFillPaint.setTextAlign(Paint.Align.CENTER);
            subTitleFillPaint.setTypeface(normalSans);

            seaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            seaPaint.setColor(isDark ? Color.argb(175, 138, 180, 248) : Color.argb(180, 23, 78, 166));
            seaPaint.setTextSize(9.5f * density);
            seaPaint.setTextAlign(Paint.Align.CENTER);
            seaPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC));

            islandDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            islandDotPaint.setStyle(Paint.Style.FILL);
            islandDotPaint.setColor(Color.parseColor("#EA4335"));

            islandDotRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            islandDotRingPaint.setStyle(Paint.Style.STROKE);
            islandDotRingPaint.setStrokeWidth(1.3f * density);
            islandDotRingPaint.setColor(Color.WHITE);

            islandLabelHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            islandLabelHaloPaint.setStyle(Paint.Style.STROKE);
            islandLabelHaloPaint.setStrokeJoin(Paint.Join.ROUND);
            islandLabelHaloPaint.setStrokeWidth(2.6f * density);
            islandLabelHaloPaint.setColor(haloColor);
            islandLabelHaloPaint.setTextSize(8.8f * density);
            islandLabelHaloPaint.setTypeface(normalSans);

            islandLabelFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            islandLabelFillPaint.setStyle(Paint.Style.FILL);
            islandLabelFillPaint.setColor(isDark ? Color.parseColor("#E8EAED") : Color.parseColor("#3C4043"));
            islandLabelFillPaint.setTextSize(8.8f * density);
            islandLabelFillPaint.setTypeface(normalSans);
        }

        @Override
        public void draw(Canvas canvas, Projection proj) {
            if (canvas == null || proj == null) return;
            double zoom = proj.getZoomLevel();
            // Hide at world & continent map levels (< 4.2); show from Vietnam map level (>= 4.2) and closer
            if (zoom < 4.2) return;

            Rect screenRect = proj.getIntrinsicScreenRect();
            int width = screenRect != null ? screenRect.width() : canvas.getWidth();
            int height = screenRect != null ? screenRect.height() : canvas.getHeight();
            int tightMargin = (int) (24 * density);

            if (zoom <= 8.0) {
                proj.toPixels(BIEN_DONG_CENTER, screenPt);
                if (isVisible(screenPt, width, height, tightMargin)) {
                    canvas.drawText("Biển Đông", screenPt.x, screenPt.y, seaPaint);
                }
            }

            boolean showIndividualNames = zoom >= 7.5;
            drawIslands(canvas, proj, width, height, tightMargin, HOANG_SA_ISLANDS, HOANG_SA_NAMES, showIndividualNames);
            drawIslands(canvas, proj, width, height, tightMargin, TRUONG_SA_ISLANDS, TRUONG_SA_NAMES, showIndividualNames);

            String hoangSaSub = zoom >= 6.0 ? "TP. Đà Nẵng • Việt Nam" : "Việt Nam";
            String truongSaSub = zoom >= 6.0 ? "T. Khánh Hòa • Việt Nam" : "Việt Nam";
            drawArchipelagoLabel(canvas, proj, width, height, tightMargin,
                    HOANG_SA_CENTER, "Quần đảo Hoàng Sa", hoangSaSub);
            drawArchipelagoLabel(canvas, proj, width, height, tightMargin,
                    TRUONG_SA_CENTER, "Quần đảo Trường Sa", truongSaSub);
        }

        private void drawIslands(Canvas canvas, Projection proj, int width, int height, int margin,
                                 GeoPoint[] points, String[] names, boolean showNames) {
            float radius = 2.6f * density;
            for (int i = 0; i < points.length; i++) {
                proj.toPixels(points[i], screenPt);
                if (!isVisible(screenPt, width, height, margin)) continue;
                canvas.drawCircle(screenPt.x, screenPt.y, radius + 0.7f * density, islandDotRingPaint);
                canvas.drawCircle(screenPt.x, screenPt.y, radius, islandDotPaint);
                if (showNames && i < names.length) {
                    float labelX = screenPt.x + 5.5f * density;
                    float labelY = screenPt.y + 3f * density;
                    canvas.drawText(names[i], labelX, labelY, islandLabelHaloPaint);
                    canvas.drawText(names[i], labelX, labelY, islandLabelFillPaint);
                }
            }
        }

        private void drawArchipelagoLabel(Canvas canvas, Projection proj, int width, int height, int margin,
                                          GeoPoint center, String title, String subtitle) {
            proj.toPixels(center, screenPt);
            if (!isVisible(screenPt, width, height, margin)) return;

            // Place label cleanly centered directly beneath the archipelago's true center so islands stay visible
            float titleY = screenPt.y + 14f * density;
            float subY = titleY + 11.5f * density;

            canvas.drawText(title, screenPt.x, titleY, titleHaloPaint);
            canvas.drawText(title, screenPt.x, titleY, titleFillPaint);

            canvas.drawText(subtitle, screenPt.x, subY, subTitleHaloPaint);
            canvas.drawText(subtitle, screenPt.x, subY, subTitleFillPaint);
        }

        private static boolean isVisible(Point pt, int width, int height, int margin) {
            return pt.x >= -margin && pt.x <= width + margin
                    && pt.y >= -margin && pt.y <= height + margin;
        }
    }
}
