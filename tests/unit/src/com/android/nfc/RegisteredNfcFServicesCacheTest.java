
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

package com.android.nfc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.nfc.cardemulation.HostNfcFService;
import android.nfc.cardemulation.NfcFServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.nfc.cardemulation.HostNfcFEmulationManager;
import com.android.nfc.cardemulation.RegisteredNfcFServicesCache;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class RegisteredNfcFServicesCacheTest {

    private static String TAG = RegisteredNfcFServicesCacheTest.class.getSimpleName();
    private MockitoSession mStaticMockSession;
    private RegisteredNfcFServicesCache mNfcFServicesCache;
    private int mUserId = -1;
    private RegisteredNfcFServicesCache.Callback mCallback;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mStaticMockSession = ExtendedMockito.mockitoSession()
                .mockStatic(Xml.class)
                .mockStatic(NfcStatsLog.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        Context mockContext = new ContextWrapper(context) {
            public Intent registerReceiverForAllUsers(@Nullable BroadcastReceiver receiver,
                    @NonNull IntentFilter filter, @Nullable String broadcastPermission,
                    @Nullable Handler scheduler) {
                return mock(Intent.class);
            }

            public Context createPackageContextAsUser(
                    @NonNull String packageName, @CreatePackageOptions int flags,
                    @NonNull UserHandle user)
                    throws PackageManager.NameNotFoundException {
                Log.d(TAG, "createPackageContextAsUser called");
                return this;
            }

            public PackageManager getPackageManager() {
                PackageManager packageManager = mock(PackageManager.class);
                ResolveInfo resolveInfo = mock(ResolveInfo.class);
                ServiceInfo serviceInfo = mock(ServiceInfo.class);
                serviceInfo.name = "nfc";
                serviceInfo.packageName = "com.android.nfc";
                serviceInfo.permission = android.Manifest.permission.BIND_NFC_SERVICE;
                ApplicationInfo applicationInfo = mock(ApplicationInfo.class);
                serviceInfo.applicationInfo = applicationInfo;
                Resources resources = mock(Resources.class);

                try {
                    when(packageManager.getResourcesForApplication(applicationInfo)).thenReturn(
                            resources);
                } catch (PackageManager.NameNotFoundException e) {
                    throw new RuntimeException(e);
                }
                XmlResourceParser parser = mock(XmlResourceParser.class);
                AttributeSet attributeSet = mock(AttributeSet.class);

                TypedArray typedArrayHost = mock(TypedArray.class);
                when(typedArrayHost.getString(
                        com.android.internal.R.styleable.HostNfcFService_description)).thenReturn(
                        "nfc");
                TypedArray typedArrayNfcid2 = mock(TypedArray.class);
                when(typedArrayNfcid2.getString(
                        com.android.internal.R.styleable.Nfcid2Filter_name)).thenReturn(
                        "02FEC1DE32456789");
                TypedArray typedArraySystem = mock(TypedArray.class);
                when(typedArraySystem.getString(
                        com.android.internal.R.styleable.SystemCodeFilter_name)).thenReturn(
                        "42BC");
                when(resources.obtainAttributes(any(), any())).thenReturn(typedArrayHost,
                        typedArraySystem, typedArrayNfcid2);

                when(Xml.asAttributeSet(parser)).thenReturn(attributeSet);
                try {
                    when(parser.getEventType()).thenReturn(XmlPullParser.START_TAG,
                            XmlPullParser.END_TAG);
                    when(parser.next()).thenReturn(XmlPullParser.START_TAG, XmlPullParser.START_TAG,
                            XmlPullParser.END_TAG);
                    when(parser.getName()).thenReturn("host-nfcf-service",
                            "system-code-filter", "nfcid2-filter");
                } catch (XmlPullParserException e) {
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                when(serviceInfo.loadXmlMetaData(packageManager,
                        HostNfcFService.SERVICE_META_DATA)).thenReturn(parser);
                serviceInfo.loadXmlMetaData(packageManager, HostNfcFService.SERVICE_META_DATA);
                resolveInfo.serviceInfo = serviceInfo;
                List<ResolveInfo> list = new ArrayList<>();
                list.add(resolveInfo);
                when(packageManager.queryIntentServicesAsUser(any(),
                        any(),
                        any())).thenReturn(list);
                return packageManager;
            }

        };

        mCallback = mock(RegisteredNfcFServicesCache.Callback.class);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> mNfcFServicesCache =
                        new RegisteredNfcFServicesCache(mockContext, mCallback));
        Assert.assertNotNull(mNfcFServicesCache);

    }

    @After
    public void tearDown() throws Exception {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testOnHostEmulationActivated() {
        boolean isActive = mNfcFServicesCache.isActivated();
        Assert.assertFalse(isActive);
        mNfcFServicesCache.onHostEmulationActivated();
        isActive = mNfcFServicesCache.isActivated();
        Assert.assertTrue(isActive);
    }

    @Test
    public void testOnHostEmulationDeactivated() {
        mNfcFServicesCache.onHostEmulationActivated();
        boolean isActive = mNfcFServicesCache.isActivated();
        Assert.assertTrue(isActive);
        mNfcFServicesCache.onHostEmulationDeactivated();
        isActive = mNfcFServicesCache.isActivated();
        Assert.assertFalse(isActive);
    }

    @Test
    public void testOnNfcDisabled() {
        mNfcFServicesCache.onHostEmulationActivated();
        boolean isActive = mNfcFServicesCache.isActivated();
        Assert.assertTrue(isActive);
        mNfcFServicesCache.onNfcDisabled();
        isActive = mNfcFServicesCache.isActivated();
        Assert.assertFalse(isActive);
    }

    @Test
    public void testInvalidateCache() {
        Assert.assertEquals(-1, mUserId);
        mNfcFServicesCache.invalidateCache(1);
        List<NfcFServiceInfo> services = mNfcFServicesCache.getServices(1);
        Assert.assertNotNull(services);
        Assert.assertTrue(services.size() > 0);
        ExtendedMockito.verify(mCallback).onNfcFServicesUpdated(1, services);
        NfcFServiceInfo nfcFServiceInfo = services.get(0);
        ComponentName cName = nfcFServiceInfo.getComponent();
        Assert.assertNotNull(cName);
        Assert.assertEquals("com.android.nfc", cName.getPackageName());
    }


    @Test
    public void testGetService() {
        mNfcFServicesCache.invalidateCache(1);
        List<NfcFServiceInfo> services = mNfcFServicesCache.getServices(1);
        Assert.assertNotNull(services);
        Assert.assertTrue(services.size() > 0);
        NfcFServiceInfo nfcFServiceInfo = services.get(0);
        ComponentName cName = nfcFServiceInfo.getComponent();
        Assert.assertNotNull(cName);

        NfcFServiceInfo serviceInfo = mNfcFServicesCache.getService(1, cName);
        Assert.assertNotNull(serviceInfo);
        Assert.assertEquals(nfcFServiceInfo, serviceInfo);
    }

    @Test
    public void testGetNfcid2ForService() {
        mNfcFServicesCache.invalidateCache(1);
        List<NfcFServiceInfo> services = mNfcFServicesCache.getServices(1);
        Assert.assertNotNull(services);
        Assert.assertTrue(services.size() > 0);
        NfcFServiceInfo nfcFServiceInfo = services.get(0);
        ComponentName cName = nfcFServiceInfo.getComponent();
        Assert.assertNotNull(cName);

        String nfcId2 = mNfcFServicesCache.getNfcid2ForService(1, 0, cName);
        Assert.assertNotNull(nfcId2);
        Assert.assertEquals("02FEC1DE32456789", nfcId2);
    }

    @Test
    public void testSetNfcid2ForService() {
        mNfcFServicesCache.invalidateCache(1);
        List<NfcFServiceInfo> services = mNfcFServicesCache.getServices(1);
        Assert.assertNotNull(services);
        Assert.assertTrue(services.size() > 0);
        NfcFServiceInfo nfcFServiceInfo = services.get(0);
        ComponentName cName = nfcFServiceInfo.getComponent();
        Assert.assertNotNull(cName);
        XmlSerializer xmlSerializer = mock(XmlSerializer.class);
        when(Xml.newSerializer()).thenReturn(xmlSerializer);
        String nfcId2 = "02FE9876543210AB";
        boolean isSet = mNfcFServicesCache.setNfcid2ForService(1, 0, cName, nfcId2);
        Assert.assertTrue(isSet);
    }

    @Test
    public void testRemoveSystemCodeForService() {
        mNfcFServicesCache.invalidateCache(1);
        List<NfcFServiceInfo> services = mNfcFServicesCache.getServices(1);
        Assert.assertNotNull(services);
        Assert.assertTrue(services.size() > 0);
        NfcFServiceInfo nfcFServiceInfo = services.get(0);
        ComponentName cName = nfcFServiceInfo.getComponent();
        Assert.assertNotNull(cName);
        XmlSerializer xmlSerializer = mock(XmlSerializer.class);
        when(Xml.newSerializer()).thenReturn(xmlSerializer);
        boolean isRemove = mNfcFServicesCache.removeSystemCodeForService(1, 0, cName);
        Assert.assertTrue(isRemove);

    }

    @Test
    public void  testHasService() {
        mNfcFServicesCache.invalidateCache(1);
        List<NfcFServiceInfo> services = mNfcFServicesCache.getServices(1);
        Assert.assertNotNull(services);
        Assert.assertTrue(services.size() > 0);
        NfcFServiceInfo nfcFServiceInfo = services.get(0);
        ComponentName cName = nfcFServiceInfo.getComponent();
        Assert.assertNotNull(cName);
        boolean hasService = mNfcFServicesCache.hasService(1, cName);
        Assert.assertTrue(hasService);

    }

    @Test
    public void testGetSystemCodeForService() {
        mNfcFServicesCache.invalidateCache(1);
        List<NfcFServiceInfo> services = mNfcFServicesCache.getServices(1);
        Assert.assertNotNull(services);
        Assert.assertFalse(services.isEmpty());
        NfcFServiceInfo nfcFServiceInfo = services.get(0);
        ComponentName cName = nfcFServiceInfo.getComponent();
        String systemCode = mNfcFServicesCache.getSystemCodeForService(1, 0, cName);
        Assert.assertNotNull(systemCode);
        Assert.assertEquals("42BC", systemCode);
    }
}