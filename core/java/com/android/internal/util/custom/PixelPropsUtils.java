/*
 * Copyright (C) 2020 The Pixel Experience Project
 *               2021-2022 crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.internal.util.custom;

import android.os.Build;
import android.os.SystemProperties;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class PixelPropsUtils {

    public static final String PACKAGE_GMS = "com.google.android.gms";
    public static final String PACKAGE_NETFLIX = "com.netflix.mediaclient";
    private static final String DEVICE = "ro.nad.device";
    private static final String TAG = PixelPropsUtils.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final Map<String, Object> propsToChangePixel6;

    private static final Map<String, Object> propsToChangePixel5;
    private static final String[] packagesToChangePixel5 = {
            "com.google.android.apps.recorder",
            "com.google.android.apps.translate",
            "com.google.android.apps.turbo",
            "com.google.android.apps.turboadapter",
            "com.google.android.apps.wearables.maestro.companion",
            "com.google.android.googlequicksearchbox",
            "com.google.android.tts",
            "com.google.audio.hearing.visualization.accessibility.scribe"
    };

    private static final Map<String, Object> propsToChangePixelXL;
    private static final String[] packagesToChangePixelXL = {
            "com.google.android.apps.photos",
            "com.samsung.accessory",
            "com.samsung.accessory.fridaymgr",
            "com.samsung.accessory.berrymgr",
            "com.samsung.accessory.neobeanmgr",
            "com.samsung.android.app.watchmanager",
            "com.samsung.android.geargplugin",
            "com.samsung.android.gearnplugin",
            "com.samsung.android.modenplugin",
            "com.samsung.android.neatplugin",
            "com.samsung.android.waterplugin"
    };

    private static final Map<String, ArrayList<String>> propsToKeep;
    private static final String[] extraPackagesToChange = {
            "com.android.chrome",
            "com.breel.wallpapers20",
            PACKAGE_NETFLIX
    };

    private static final String[] packagesToKeep = {
            "com.google.android.GoogleCamera",
            "com.google.android.GoogleCamera.Cameight",
            "com.google.android.GoogleCamera.Go",
            "com.google.android.GoogleCamera.Urnyx",
            "com.google.android.GoogleCameraAsp",
            "com.google.android.GoogleCameraCVM",
            "com.google.android.GoogleCameraEng",
            "com.google.android.GoogleCameraEng2",
            "com.google.android.GoogleCameraGood",
            "com.google.android.MTCL83",
            "com.google.android.UltraCVM",
            "com.google.android.apps.cameralite",
            "com.google.android.dialer",
            "com.google.android.youtube",
            "com.google.ar.core"
    };

    // Codenames for currently supported Pixels by Google
    private static final String[] pixelCodenames = {
            "oriole",
            "raven",
            "redfin",
            "barbet",
            "bramble",
            "sunfish",
            "coral",
            "flame",
            "bonito",
            "sargo"
    };

    private static volatile boolean sIsGms = false;

    static {
        propsToKeep = new HashMap<>();
        propsToKeep.put("com.google.android.settings.intelligence", new ArrayList<>(Collections.singletonList("FINGERPRINT")));
        propsToChangePixel6 = new HashMap<>();
        propsToChangePixel6.put("BRAND", "google");
        propsToChangePixel6.put("MANUFACTURER", "Google");
        propsToChangePixel6.put("DEVICE", "raven");
        propsToChangePixel6.put("PRODUCT", "raven");
        propsToChangePixel6.put("MODEL", "Pixel 6 Pro");
        propsToChangePixel6.put("FINGERPRINT", "google/raven/raven:12/S3B1.220218.004/8242181:user/release-keys");
        propsToChangePixel5 = new HashMap<>();
        propsToChangePixel5.put("BRAND", "google");
        propsToChangePixel5.put("MANUFACTURER", "Google");
        propsToChangePixel5.put("DEVICE", "redfin");
        propsToChangePixel5.put("PRODUCT", "redfin");
        propsToChangePixel5.put("MODEL", "Pixel 5");
        propsToChangePixel5.put("FINGERPRINT", "google/redfin/redfin:12/SP2A.220305.012/8177914:user/release-keys");
        propsToChangePixelXL = new HashMap<>();
        propsToChangePixelXL.put("BRAND", "google");
        propsToChangePixelXL.put("MANUFACTURER", "Google");
        propsToChangePixelXL.put("DEVICE", "marlin");
        propsToChangePixelXL.put("PRODUCT", "marlin");
        propsToChangePixelXL.put("MODEL", "Pixel XL");
        propsToChangePixelXL.put("FINGERPRINT", "google/marlin/marlin:10/QP1A.191005.007.A3/5972272:user/release-keys");
    }

    public static void setProps(String packageName) {
        if (packageName == null) {
            return;
        }
        if (packageName.equals(PACKAGE_GMS)) {
            sIsGms = true;
        }
        if (packageName.equals(PACKAGE_NETFLIX) && !SystemProperties.getBoolean(
                "persist.pixelpropsutils.spoof_netflix", true)) {
            if (DEBUG) Log.d(TAG, "Netflix spoofing disabled by system prop");
            return;
        }
        boolean isPixelDevice = Arrays.asList(pixelCodenames).contains(SystemProperties.get(DEVICE));
        if (!isPixelDevice &&
            ((packageName.startsWith("com.google.") || packageName.startsWith("com.chrome.") && !Arrays.asList(packagesToKeep).contains(packageName))
                || Arrays.asList(extraPackagesToChange).contains(packageName))) {
            Map<String, Object> propsToChange = propsToChangePixel6;

            if (Arrays.asList(packagesToChangePixel5).contains(packageName)) {
                propsToChange = propsToChangePixel5;
            }
  
            if (Arrays.asList(packagesToChangePixelXL).contains(packageName)) {
                final String sysPropSpoof = SystemProperties.get("persist.sys.photo", "1");
                boolean dontSpoof = ("0".equals(sysPropSpoof)) ? true : false;
                if (dontSpoof && packageName.equals("com.google.android.apps.photos")) return;
                propsToChange = propsToChangePixelXL;
            }

            if (DEBUG) Log.d(TAG, "Defining props for: " + packageName);
            for (Map.Entry<String, Object> prop : propsToChange.entrySet()) {
                String key = prop.getKey();
                Object value = prop.getValue();
                if (propsToKeep.containsKey(packageName) && propsToKeep.get(packageName).contains(key)) {
                    if (DEBUG) Log.d(TAG, "Not defining " + key + " prop for: " + packageName);
                    continue;
                }
                if (DEBUG) Log.d(TAG, "Defining " + key + " prop for: " + packageName);
                setPropValue(key, value);
            }
            if (isPixelDevice) {
                if (packageName.equals(PACKAGE_GMS)) {
                    setPropValue("MODEL", Build.MODEL + " ");
                }
            }
            // Set proper indexing fingerprint
            if (packageName.equals("com.google.android.settings.intelligence")) {
                setPropValue("FINGERPRINT", Build.VERSION.INCREMENTAL);
            }
        }
    }

    private static void setPropValue(String key, Object value) {
        try {
            if (DEBUG) Log.d(TAG, "Defining prop " + key + " to " + value.toString());
            Field field = Build.class.getDeclaredField(key);
            field.setAccessible(true);
            field.set(null, value);
            field.setAccessible(false);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Failed to set prop " + key, e);
        }
    }

    private static boolean isCallerSafetyNet() {
        return Arrays.stream(Thread.currentThread().getStackTrace())
                .anyMatch(elem -> elem.getClassName().contains("DroidGuard"));
    }

    public static void onEngineGetCertificateChain() {
        // Check stack for SafetyNet
        if (sIsGms && isCallerSafetyNet()) {
            throw new UnsupportedOperationException();
        }
    }
}
