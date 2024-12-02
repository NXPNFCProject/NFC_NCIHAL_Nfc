/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.nfc.emulator;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;
import android.content.res.XmlResourceParser;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;

import com.android.compatibility.common.util.CommonTestUtils;
import com.android.nfc.service.HceService;
import com.android.nfc.utils.HceUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public abstract class BaseEmulatorActivity extends Activity {
    public static final String PACKAGE_NAME = "com.android.nfc.emulator";

    public static final String ACTION_ROLE_HELD = PACKAGE_NAME + ".ACTION_ROLE_HELD";

    // Intent action that's sent after the test condition is met.
    protected static final String ACTION_TEST_PASSED = PACKAGE_NAME + ".ACTION_TEST_PASSED";
    protected static final String TAG = "BaseEmulatorActivity";
    protected NfcAdapter mAdapter;
    protected CardEmulation mCardEmulation;
    protected RoleManager mRoleManager;

    final BroadcastReceiver mReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (HceService.ACTION_APDU_SEQUENCE_COMPLETE.equals(action)) {
                        // Get component whose sequence was completed
                        ComponentName component =
                                intent.getParcelableExtra(HceService.EXTRA_COMPONENT);
                        long duration = intent.getLongExtra(HceService.EXTRA_DURATION, 0);
                        if (component != null) {
                            onApduSequenceComplete(component, duration);
                        }
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        mAdapter = NfcAdapter.getDefaultAdapter(this);
        mCardEmulation = CardEmulation.getInstance(mAdapter);
        mRoleManager = getSystemService(RoleManager.class);
        IntentFilter filter = new IntentFilter(HceService.ACTION_APDU_SEQUENCE_COMPLETE);
        registerReceiver(mReceiver, filter, RECEIVER_EXPORTED);
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mReceiver);
        disableServices();
    }

    public void disableServices() {
        for (ComponentName component : getServices()) {
            Log.d(TAG, "Disabling component " + component);
            HceUtils.disableComponent(getPackageManager(), component);
        }
    }

    /* Gets preferred service description */
    public String getServiceDescriptionFromComponent(ComponentName component) {
        try {
            Bundle data =
                    getPackageManager()
                            .getServiceInfo(component, PackageManager.GET_META_DATA)
                            .metaData;
            XmlResourceParser xrp =
                    getResources().getXml(data.getInt(HostApduService.SERVICE_META_DATA));
            boolean parsing = true;
            while (parsing) {
                try {
                    switch (xrp.next()) {
                        case XmlResourceParser.END_DOCUMENT:
                            parsing = false;
                            break;
                        case XmlResourceParser.START_TAG:
                            if (xrp.getName().equals("host-apdu-service")) {
                                AttributeSet set = Xml.asAttributeSet(xrp);
                                int resId =
                                        set.getAttributeResourceValue(
                                                "http://schemas.android.com/apk/res/android",
                                                "description",
                                                -1);
                                if (resId != -1) {
                                    return getResources().getString(resId);
                                }
                                return "";
                            }
                            break;
                        default:
                            break;
                    }
                } catch (XmlPullParserException | IOException e) {
                    Log.d(TAG, "error: " + e.toString());
                    throw new IllegalStateException(
                            "Resource parsing failed. This shouldn't happen.", e);
                }
            }
        } catch (NameNotFoundException e) {
            Log.w(TAG, "NameNotFoundException. Test will probably fail.");
        } catch (Exception e) {
            Log.w(TAG, "Exception while parsing service description.", e);
        }
        return "";
    }

    void ensurePreferredService(String serviceDesc, Context context, CardEmulation cardEmulation) {
        Log.d(TAG, "ensurePreferredService: " + serviceDesc);
        try {
            CommonTestUtils.waitUntil(
                    "Default service hasn't updated",
                    6,
                    () ->
                            cardEmulation.getDescriptionForPreferredPaymentService() != null
                                    && serviceDesc.equals(
                                            cardEmulation
                                                    .getDescriptionForPreferredPaymentService()
                                                    .toString()));
        } catch (Exception e) {
            Log.e(TAG, "Default service not updated. This may cause tests to fail", e);
        }
    }

    /** Sets observe mode. */
    public boolean setObserveModeEnabled(boolean enable) {
        ensurePreferredService(
                getServiceDescriptionFromComponent(getPreferredServiceComponent()),
                this,
                mCardEmulation);
        return mAdapter.setObserveModeEnabled(enable);
    }

    /** Waits for preferred service to be set, and sends broadcast afterwards. */
    public void waitForPreferredService() {
        ensurePreferredService(
                getServiceDescriptionFromComponent(getPreferredServiceComponent()),
                this,
                mCardEmulation);
    }

    /** Waits for given service to be set */
    public void waitForService(ComponentName componentName) {
        ensurePreferredService(
                getServiceDescriptionFromComponent(componentName), this, mCardEmulation);
    }

    void waitForObserveModeEnabled(boolean enabled) {
        Log.d(TAG, "waitForObserveModeEnabled: " + enabled);
        try {
            CommonTestUtils.waitUntil("Observe mode has not been set", 6,
                    () -> mAdapter.isObserveModeEnabled() == enabled);
        } catch (Exception e) {
            Log.e(TAG, "Observe mode not set to " + enabled + ". This may cause tests to fail", e);
        }
    }

    public abstract ComponentName getPreferredServiceComponent();

    public boolean isObserveModeEnabled() {
        return mAdapter.isObserveModeEnabled();
    }

    /** Sets up HCE services for this emulator */
    public void setupServices(ComponentName... componentNames) {
        List<ComponentName> enableComponents = Arrays.asList(componentNames);
        for (ComponentName component : getServices()) {
            if (enableComponents.contains(component)) {
                Log.d(TAG, "Enabling component " + component);
                HceUtils.enableComponent(getPackageManager(), component);
            } else {
                Log.d(TAG, "Disabling component " + component);
                HceUtils.disableComponent(getPackageManager(), component);
            }
        }
        ComponentName bogusComponent =
                new ComponentName(
                        PACKAGE_NAME,
                        PACKAGE_NAME + ".BogusService");
        mCardEmulation.isDefaultServiceForCategory(bogusComponent, CardEmulation.CATEGORY_PAYMENT);

        onServicesSetup();
    }

    /** Executed after services are set up */
    protected void onServicesSetup() {}

    /** Executed after successful APDU sequence received */
    protected void onApduSequenceComplete(ComponentName component, long duration) {}

    /** Call this in child classes when test condition is met */
    protected void setTestPassed() {
        Intent intent = new Intent(ACTION_TEST_PASSED);
        sendBroadcast(intent);
    }

    /** Makes this package the default wallet role holder */
    public void makeDefaultWalletRoleHolder() {
        if (!isWalletRoleHeld()) {
            Log.d(TAG, "Wallet Role not currently held. Setting default role now");
            setDefaultWalletRole();
        } else {
            Intent intent = new Intent(ACTION_ROLE_HELD);
            sendBroadcast(intent);
        }
    }

    protected boolean isWalletRoleHeld() {
        assert mRoleManager != null;
        return mRoleManager.isRoleHeld(RoleManager.ROLE_WALLET);
    }

    protected void setDefaultWalletRole() {
        if (HceUtils.setDefaultWalletRoleHolder(this, PACKAGE_NAME)) {
            Log.d(TAG, "Default role holder set: " + isWalletRoleHeld());
            Intent intent = new Intent(ACTION_ROLE_HELD);
            sendBroadcast(intent);
        }
    }

    /** Set Listen tech */
    public void setListenTech(int listenTech) {
        mAdapter.setDiscoveryTechnology(this, NfcAdapter.FLAG_READER_KEEP, listenTech);
    }

    /** Reset Listen tech */
    public void resetListenTech() {
        mAdapter.resetDiscoveryTechnology(this);
    }

    /* Fetch all services in the package */
    private List<ComponentName> getServices() {
        List<ComponentName> services = new ArrayList<>();
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(PACKAGE_NAME,
                    PackageManager.GET_SERVICES
                            | PackageManager.MATCH_DISABLED_COMPONENTS);

            if (packageInfo.services != null) {
                for (ServiceInfo info : packageInfo.services) {
                    services.add(new ComponentName(PACKAGE_NAME, info.name));
                }
            }

        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Package, application or component name cannot be found", e);
        }
        return services;
    }
}
