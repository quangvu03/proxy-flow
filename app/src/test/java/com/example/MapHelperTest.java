package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Point;

import androidx.test.core.app.ApplicationProvider;

import com.example.ui.MapHelper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.osmdroid.api.IGeoPoint;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
public class MapHelperTest {

    @Test
    @Config(qualifiers = "xxhdpi")
    public void vietnamArchipelagoProjection_xxhdpi_roundTripsExactly() {
        Context context = ApplicationProvider.getApplicationContext();
        MapView mapView = new MapView(context);
        MapHelper.configureMapView(context, mapView, 5.5);

        mapView.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(840, android.view.View.MeasureSpec.EXACTLY)
        );
        mapView.layout(0, 0, 1080, 840);
        mapView.getController().setZoom(6.0);
        mapView.getController().setCenter(new GeoPoint(14.20, 109.80));

        Projection proj = mapView.getProjection();
        GeoPoint hoangSaGeo = new GeoPoint(16.5347, 111.9500);
        GeoPoint truongSaGeo = new GeoPoint(9.5500, 113.3500);
        GeoPoint daNangGeo = new GeoPoint(16.0544, 108.2022);
        GeoPoint hcmGeo = new GeoPoint(10.8231, 106.6297);

        Point daNang = proj.toPixels(daNangGeo, null);
        Point hoangSa = proj.toPixels(hoangSaGeo, null);
        Point truongSa = proj.toPixels(truongSaGeo, null);
        Point hcm = proj.toPixels(hcmGeo, null);

        IGeoPoint backHoangSa = proj.fromPixels(hoangSa.x, hoangSa.y);
        IGeoPoint backTruongSa = proj.fromPixels(truongSa.x, truongSa.y);

        System.out.println("XXHDPI DaNang=" + daNang + " HoangSa=" + hoangSa
                + " HCM=" + hcm + " TruongSa=" + truongSa
                + " backHS=" + backHoangSa + " backTS=" + backTruongSa);

        assertEquals(hoangSaGeo.getLatitude(), backHoangSa.getLatitude(), 0.15);
        assertEquals(hoangSaGeo.getLongitude(), backHoangSa.getLongitude(), 0.15);
        assertEquals(truongSaGeo.getLatitude(), backTruongSa.getLatitude(), 0.15);
        assertEquals(truongSaGeo.getLongitude(), backTruongSa.getLongitude(), 0.15);

        assertTrue(hoangSa.x > daNang.x);
        assertTrue(hoangSa.y < daNang.y);
        assertTrue(truongSa.x > hcm.x);
        assertTrue(truongSa.y > hoangSa.y);
    }
}
