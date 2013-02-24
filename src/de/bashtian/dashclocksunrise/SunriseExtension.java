/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.bashtian.dashclocksunrise;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Criteria;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.text.TextUtils;

import android.text.format.DateFormat;
import android.util.Log;
import com.google.android.apps.dashclock.api.DashClockExtension;
import com.google.android.apps.dashclock.api.ExtensionData;
import com.luckycatlabs.sunrisesunset.SunriseSunsetCalculator;
import com.luckycatlabs.sunrisesunset.dto.Location;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;

public class SunriseExtension extends DashClockExtension {
    private static final String TAG = "SunriseExtension";

    public static final String PREF_SHOW_BEFORE_HOURS = "pref_sunrise_show_before_hours";

    private static final Criteria sLocationCriteria;

    private boolean mOneTimeLocationListenerActive = false;

    private static final long MINUTE_MILLIS = 60 * 1000;
    private static final long HOUR_MILLIS = 60 * MINUTE_MILLIS;

    private static final int DEFAULT_SHOW_BEFORE_HOURS = 0;
    private int mShowBeforeHours = DEFAULT_SHOW_BEFORE_HOURS;

    private static final long STALE_LOCATION_NANOS = 10l * 60000000000l; // 10 minutes

    static {
        sLocationCriteria = new Criteria();
        sLocationCriteria.setPowerRequirement(Criteria.POWER_LOW);
        sLocationCriteria.setAccuracy(Criteria.ACCURACY_COARSE);
        sLocationCriteria.setCostAllowed(false);
    }

    @Override
    protected void onUpdateData(int reason) {
        Log.d(TAG, "sunrise onUpdateData: " + sLocationCriteria.toString());
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        String provider = lm.getBestProvider(sLocationCriteria, true);
        if (TextUtils.isEmpty(provider)) {
            Log.d(TAG, "No available location providers matching criteria." + lm.getAllProviders());
            return;
        }

        final android.location.Location lastLocation = lm.getLastKnownLocation(provider);

        if (lastLocation == null ||
                (SystemClock.elapsedRealtimeNanos() - lastLocation.getElapsedRealtimeNanos())
                        >= STALE_LOCATION_NANOS) {
            Log.d(TAG, "Stale or missing last-known location; requesting single coarse location "
                    + "update.");
            disableOneTimeLocationListener();
            mOneTimeLocationListenerActive = true;
            lm.requestSingleUpdate(provider, mOneTimeLocationListener, null);
        } else {
            publishUpdate(lastLocation);
        }

    }

    private void disableOneTimeLocationListener() {
        if (mOneTimeLocationListenerActive) {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            lm.removeUpdates(mOneTimeLocationListener);
            mOneTimeLocationListenerActive = false;
        }
    }


    private void publishUpdate(android.location.Location lastLocation) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);

        try {
            mShowBeforeHours = Integer.parseInt(sp.getString(PREF_SHOW_BEFORE_HOURS,
                    Integer.toString(mShowBeforeHours)));
        } catch (NumberFormatException e) {
            mShowBeforeHours = DEFAULT_SHOW_BEFORE_HOURS;
        }


        Location location = new Location(lastLocation.getLatitude(), lastLocation.getLongitude());
        SunriseSunsetCalculator calculator = new SunriseSunsetCalculator(location, TimeZone.getDefault().getID());

        Calendar now = Calendar.getInstance();
        Calendar sunset = calculator.getOfficialSunsetCalendarForDate(now);

        // when time is after sunset, use the next day
        if (now.after(sunset)) {
            now.add(Calendar.DATE, 1);
            sunset = calculator.getOfficialSunsetCalendarForDate(now);
        }

        Calendar sunrise = calculator.getOfficialSunriseCalendarForDate(now);

        boolean isBeforeSunset = now.before(sunset);
        boolean isBeforeSunrise = now.before(sunrise);

        Log.d(TAG, "before sunrise: " + isBeforeSunrise + " before sunset:" + isBeforeSunset);

        String inFormat;
        if (DateFormat.is24HourFormat(this)) {
            inFormat = "HH:mm";
        } else {
            inFormat = "h:mm a";
        }

        CharSequence officialSunriseForDate = new SimpleDateFormat(inFormat)
                .format(sunrise.getTime());
        CharSequence officialSunsetForDate = new SimpleDateFormat(inFormat)
                .format(sunset.getTime());
        String sunriseText = getString(R.string.expanded_title_template, officialSunriseForDate, "Sunrise");
        String sunsetText = getString(R.string.expanded_title_template, officialSunsetForDate, "Sunset");

        boolean showSunset = !isBeforeSunrise && isBeforeSunset;
        boolean visible = true;

        if (mShowBeforeHours != 0) {
            long nextChange = showSunset ? sunset.getTimeInMillis() : sunrise.getTimeInMillis();
            long currentTimestamp = getCurrentTimestamp();
            visible = nextChange - currentTimestamp < mShowBeforeHours * HOUR_MILLIS;
        }

        publishUpdate(new ExtensionData()
                .visible(visible)
                .icon(R.drawable.ic_sunrise)
                .status(showSunset ? officialSunsetForDate.toString() : officialSunriseForDate.toString())
                .expandedTitle(showSunset ? sunsetText : sunriseText)
                .expandedBody(showSunset ? sunriseText : sunsetText));
                //.clickIntent(new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.google.com"))));
    }

    private static long getCurrentTimestamp() {
        return Calendar.getInstance().getTimeInMillis();
    }

    private LocationListener mOneTimeLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(android.location.Location location) {
            publishUpdate(location);
            disableOneTimeLocationListener();
        }

        @Override
        public void onStatusChanged(String s, int i, Bundle bundle) {
        }

        @Override
        public void onProviderEnabled(String s) {
        }

        @Override
        public void onProviderDisabled(String s) {
        }
    };

    @Override
    public void onDestroy() {
        super.onDestroy();
        disableOneTimeLocationListener();
    }

}
