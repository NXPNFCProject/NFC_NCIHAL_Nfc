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

import static android.Manifest.permission.BIND_NFC_SERVICE;
import static android.Manifest.permission.NFC;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

public class UtilsTest {
    @Mock
    Context context;

    @Mock
    PackageManager packageManager;

    @Mock
    PackageInfo packageInfo;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testGetPackageNameFromIntent() {
        // Prepare data for the Intent
        Intent intent = mock(Intent.class);
        Uri uri = mock(Uri.class);
        doReturn("package").when(uri).getScheme();
        doReturn("com.example.app").when(uri).getSchemeSpecificPart();
        doReturn(uri).when(intent).getData();

        // Call the method and check the result
        String packageName = Utils.getPackageNameFromIntent(intent);
        assertTrue(packageName.equals("com.example.app"));
    }

    @Test
    public void testHasCeServicesWithValidPermissions() throws PackageManager.NameNotFoundException {
        // Prepare data for the test
        Intent intent = mock(Intent.class);
        Uri uri = mock(Uri.class);
        doReturn("package").when(uri).getScheme();
        doReturn("com.example.app").when(uri).getSchemeSpecificPart();
        doReturn(uri).when(intent).getData();

        // Mock PackageManager behavior
        doReturn(context).when(context).createPackageContextAsUser(
                anyString(), anyInt(), any(UserHandle.class));
        doReturn(packageManager).when(context).getPackageManager();
        doReturn(PackageManager.PERMISSION_GRANTED).when(packageManager)
                .checkPermission(NFC, "com.example.app");
        doReturn(packageInfo).when(packageManager).getPackageInfo("com.example.app",
                PackageManager.GET_PERMISSIONS
                        | PackageManager.GET_SERVICES
                        | PackageManager.MATCH_DISABLED_COMPONENTS);
        ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.permission = BIND_NFC_SERVICE;
        packageInfo.services = new ServiceInfo[]{serviceInfo};

        // Call the method and check the result
        boolean result = Utils.hasCeServicesWithValidPermissions(context, intent, 123);
        assertTrue(result);
    }
}
