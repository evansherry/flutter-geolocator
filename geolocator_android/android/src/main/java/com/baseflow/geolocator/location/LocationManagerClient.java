package com.baseflow.geolocator.location;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.location.GnssStatusCompat;
import androidx.core.location.LocationManagerCompat;

import com.baseflow.geolocator.errors.ErrorCallback;
import com.baseflow.geolocator.errors.ErrorCodes;

import java.util.List;

class LocationManagerClient extends GnssStatusCompat.Callback implements LocationClient, LocationListener {

    private static final long TWO_MINUTES = 120000;
    private final LocationManager locationManager;
    private final NmeaClient nmeaClient;
    @Nullable
    private final LocationOptions locationOptions;
    public Context context;
    private boolean isListening = false;
    private Handler handler;

    @Nullable
    private Location currentBestLocation;
    @Nullable
    private String currentLocationProvider;
    @Nullable
    private PositionChangedCallback positionChangedCallback;
    @Nullable
    private ErrorCallback errorCallback;

    public LocationManagerClient(
            @NonNull Context context, @Nullable LocationOptions locationOptions) {
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        this.locationOptions = locationOptions;
        this.context = context;
        this.nmeaClient = new NmeaClient(context, locationOptions);
        handler = new Handler(Looper.getMainLooper());
    }

    static boolean isBetterLocation(Location location, Location bestLocation) {
        if (bestLocation == null) return true;

        long timeDelta = location.getTime() - bestLocation.getTime();
        boolean isSignificantlyNewer = timeDelta > TWO_MINUTES;
        boolean isSignificantlyOlder = timeDelta < -TWO_MINUTES;
        boolean isNewer = timeDelta > 0;

        if (isSignificantlyNewer) return true;

        if (isSignificantlyOlder) return false;

        float accuracyDelta = (int) (location.getAccuracy() - bestLocation.getAccuracy());
        boolean isLessAccurate = accuracyDelta > 0;
        boolean isMoreAccurate = accuracyDelta < 0;
        boolean isSignificantlyLessAccurate = accuracyDelta > 200;

        boolean isFromSameProvider = false;
        if (location.getProvider() != null) {
            isFromSameProvider = location.getProvider().equals(bestLocation.getProvider());
        }

        if (isMoreAccurate) return true;

        if (isNewer && !isLessAccurate) return true;

        //noinspection RedundantIfStatement
        if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) return true;

        return false;
    }

    private static String getBestProvider(
            LocationManager locationManager, LocationAccuracy accuracy) {
        Criteria criteria = new Criteria();

        criteria.setBearingRequired(false);
        criteria.setAltitudeRequired(false);
        criteria.setSpeedRequired(false);

        switch (accuracy) {
            case lowest:
                criteria.setAccuracy(Criteria.NO_REQUIREMENT);
                criteria.setHorizontalAccuracy(Criteria.NO_REQUIREMENT);
                criteria.setPowerRequirement(Criteria.NO_REQUIREMENT);
                break;
            case low:
                criteria.setAccuracy(Criteria.ACCURACY_COARSE);
                criteria.setHorizontalAccuracy(Criteria.ACCURACY_LOW);
                criteria.setPowerRequirement(Criteria.NO_REQUIREMENT);
                break;
            case medium:
                criteria.setAccuracy(Criteria.ACCURACY_COARSE);
                criteria.setHorizontalAccuracy(Criteria.ACCURACY_MEDIUM);
                criteria.setPowerRequirement(Criteria.POWER_MEDIUM);
                break;
            default:
                criteria.setAccuracy(Criteria.ACCURACY_FINE);
                criteria.setHorizontalAccuracy(Criteria.ACCURACY_HIGH);
                criteria.setPowerRequirement(Criteria.POWER_HIGH);
                break;
        }

        String provider = locationManager.getBestProvider(criteria, true);

        if (provider.trim().isEmpty()) {
            List<String> providers = locationManager.getProviders(true);
            if (providers.size() > 0) provider = providers.get(0);
        }

        return provider;
    }

    private static float accuracyToFloat(LocationAccuracy accuracy) {
        switch (accuracy) {
            case lowest:
            case low:
                return 500;
            case medium:
                return 250;
            case best:
            case bestForNavigation:
                return 50;
            default:
                return 100;
        }
    }

    @Override
    public void isLocationServiceEnabled(LocationServiceListener listener) {
        if (locationManager == null) {
            listener.onLocationServiceResult(false);
            return;
        }

        listener.onLocationServiceResult(checkLocationService(context));
    }

    @Override
    public void getLastKnownPosition(
            PositionChangedCallback positionChangedCallback, ErrorCallback errorCallback) {
        Location bestLocation = null;

        for (String provider : locationManager.getProviders(true)) {
            @SuppressLint("MissingPermission")
            Location location = locationManager.getLastKnownLocation(provider);

            if (location != null && isBetterLocation(location, bestLocation)) {
                bestLocation = location;
            }
        }

        positionChangedCallback.onPositionChanged(bestLocation);
    }

    @Override
    public boolean onActivityResult(int requestCode, int resultCode) {
        return false;
    }

    @SuppressLint("MissingPermission")
    @Override
    public void startPositionUpdates(
            Activity activity,
            PositionChangedCallback positionChangedCallback,
            ErrorCallback errorCallback) {

        if (!checkLocationService(context)) {
            errorCallback.onError(ErrorCodes.locationServicesDisabled);
            return;
        }

        this.positionChangedCallback = positionChangedCallback;
        this.errorCallback = errorCallback;

        LocationAccuracy locationAccuracy =
                this.locationOptions != null ? this.locationOptions.getAccuracy() : LocationAccuracy.best;

        this.currentLocationProvider = getBestProvider(this.locationManager, locationAccuracy);

        if (this.currentLocationProvider.trim().isEmpty()) {
            errorCallback.onError(ErrorCodes.locationServicesDisabled);
            return;
        }

        if (LocationManager.GPS_PROVIDER.equals(currentLocationProvider) && ActivityCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED) {
            // 注册卫星信号监听
//                Timber.d("registerGnssStatusCallback")
            LocationManagerCompat.registerGnssStatusCallback(
                    locationManager, ContextCompat.getMainExecutor(context), this
            );
        }

        this.isListening = true;
        this.nmeaClient.start();
        requestLocationUpdates();
    }

    @SuppressLint("MissingPermission")
    @Override
    public void stopPositionUpdates() {
        this.isListening = false;
        this.nmeaClient.stop();
        LocationManagerCompat.unregisterGnssStatusCallback(locationManager, this);
        this.locationManager.removeUpdates(this);
        currentLocationProvider = null;

    }

    @Override
    public synchronized void onLocationChanged(Location location) {
        float desiredAccuracy =
                locationOptions != null ? accuracyToFloat(locationOptions.getAccuracy()) : 50;

        if (isBetterLocation(location, currentBestLocation)
                && location.getAccuracy() <= desiredAccuracy) {
            this.currentBestLocation = location;

            if (this.positionChangedCallback != null) {
                nmeaClient.enrichExtrasWithNmea(location);
                this.positionChangedCallback.onPositionChanged(currentBestLocation);
            }
        }
    }

    @Override
    public void onSatelliteStatusChanged(@NonNull GnssStatusCompat status) {
        super.onSatelliteStatusChanged(status);
        LocationManagerCompat.unregisterGnssStatusCallback(locationManager, this);
        int validSatelliteCount = 0;
        for (int i = 0; i < status.getSatelliteCount(); i++) {
            //                Timber.d("当前卫星载波噪声密度： ${status.getCn0DbHz(i)}")
            // 载波噪声密度 大于 30 为有效卫星
            if (status.getCn0DbHz(i) > 30) {
                validSatelliteCount++;
            }
        }

        // 有效卫星数大于3表可以可以gps定位
        if (validSatelliteCount < 3) {
            retryLocationOrStop();
        }
    }

    @TargetApi(28)
    @SuppressWarnings("deprecation")
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        if (status == android.location.LocationProvider.AVAILABLE) {
            onProviderEnabled(provider);
        } else if (status == android.location.LocationProvider.OUT_OF_SERVICE) {
            onProviderDisabled(provider);
        }
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @SuppressLint("MissingPermission")
    @Override
    public void onProviderDisabled(String provider) {
        if (provider.equals(this.currentLocationProvider)) {
            if (isListening) {
                this.locationManager.removeUpdates(this);
            }

            if (this.errorCallback != null) {
                errorCallback.onError(ErrorCodes.locationServicesDisabled);
            }

            this.currentLocationProvider = null;
        }
    }

    /**
     * 定位重试
     */
    private void retryLocationOrStop() {
        // 如果是gps定位超时，且存在网络定位
        if (LocationManager.GPS_PROVIDER.equals(currentLocationProvider) && LocationManagerCompat.hasProvider(
                locationManager, LocationManager.NETWORK_PROVIDER
        )
        ) {
            currentLocationProvider = LocationManager.NETWORK_PROVIDER;
            requestLocationUpdates();
        } else {
            stopPositionUpdates();
        }
    }

    private void requestLocationUpdates() {
        long timeInterval = 0;
        float distanceFilter = 0;
        if (this.locationOptions != null) {
            timeInterval = locationOptions.getTimeInterval();
            distanceFilter = locationOptions.getDistanceFilter();
        }
        locationManager.requestLocationUpdates(
                this.currentLocationProvider, timeInterval, distanceFilter, this, Looper.getMainLooper());

    }
}
