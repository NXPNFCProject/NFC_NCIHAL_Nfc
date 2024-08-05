/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.nfc.utils;

import static android.Manifest.permission.MANAGE_DEFAULT_APPLICATIONS;
import static android.Manifest.permission.WRITE_SECURE_SETTINGS;

import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;

import com.android.nfc.service.AccessService;
import com.android.nfc.service.LargeNumAidsService;
import com.android.nfc.service.OffHostService;
import com.android.nfc.service.PaymentService1;
import com.android.nfc.service.PaymentService2;
import com.android.nfc.service.PaymentServiceDynamicAids;
import com.android.nfc.service.PollingLoopService;
import com.android.nfc.service.PrefixAccessService;
import com.android.nfc.service.PrefixPaymentService1;
import com.android.nfc.service.PrefixPaymentService2;
import com.android.nfc.service.PrefixTransportService1;
import com.android.nfc.service.PrefixTransportService2;
import com.android.nfc.service.ScreenOffPaymentService;
import com.android.nfc.service.ScreenOnOnlyOffHostService;
import com.android.nfc.service.ThroughputService;
import com.android.nfc.service.TransportService1;
import com.android.nfc.service.TransportService2;

import com.google.common.util.concurrent.MoreExecutors;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Utilites for multi-device HCE tests. */
public final class HceUtils {

    private HceUtils() {}

    public static final String MC_AID = "A0000000041010";
    public static final String PPSE_AID = "325041592E5359532E4444463031";
    public static final String VISA_AID = "A0000000030000";

    public static final String TRANSPORT_AID = "F001020304";
    public static final String SE_AID_1 = "A000000151000000";
    public static final String SE_AID_2 = "A000000003000000";
    public static final String ACCESS_AID = "F005060708";

    public static final String TRANSPORT_PREFIX_AID = "F001020304";
    public static final String ACCESS_PREFIX_AID = "F005060708";

    public static final String LARGE_NUM_AIDS_PREFIX = "F00102030414";
    public static final String LARGE_NUM_AIDS_POSTFIX = "81";

    public static final String EMULATOR_PACKAGE_NAME = "com.android.nfc.emulator";

    /** Service-specific APDU Command/Response sequences */
    public static final HashMap<String, CommandApdu[]> COMMAND_APDUS_BY_SERVICE = new HashMap<>();

    public static final HashMap<String, String[]> RESPONSE_APDUS_BY_SERVICE = new HashMap<>();

    static {
        COMMAND_APDUS_BY_SERVICE.put(
                TransportService1.class.getName(),
                new CommandApdu[] {
                        buildSelectApdu(TRANSPORT_AID, true), buildCommandApdu("80CA01E000", true)
                });

        RESPONSE_APDUS_BY_SERVICE.put(
                TransportService1.class.getName(), new String[] {"80CA9000", "83947102829000"});

        // Payment Service #1
        COMMAND_APDUS_BY_SERVICE.put(
                PaymentService1.class.getName(),
                new CommandApdu[] {
                        buildSelectApdu(PPSE_AID, true),
                        buildSelectApdu(MC_AID, true),
                        buildCommandApdu("80CA01F000", true)
                });
        RESPONSE_APDUS_BY_SERVICE.put(
                PaymentService1.class.getName(),
                new String[] {"FFFF9000", "FFEF9000", "FFDFFFAABB9000"});

        COMMAND_APDUS_BY_SERVICE.put(
                PaymentService2.class.getName(),
                new CommandApdu[] {buildSelectApdu(PPSE_AID, true), buildSelectApdu(MC_AID, true)});
        RESPONSE_APDUS_BY_SERVICE.put(
                PaymentService2.class.getName(), new String[] {"12349000", "56789000"});

        COMMAND_APDUS_BY_SERVICE.put(
                PaymentServiceDynamicAids.class.getName(),
                new CommandApdu[] {
                        buildSelectApdu(PPSE_AID, true),
                        buildSelectApdu(VISA_AID, true),
                        buildCommandApdu("80CA01F000", true)
                });
        RESPONSE_APDUS_BY_SERVICE.put(
                PaymentServiceDynamicAids.class.getName(),
                new String[] {"FFFF9000", "FF0F9000", "FFDFFFAACB9000"});

        COMMAND_APDUS_BY_SERVICE.put(
                PrefixPaymentService1.class.getName(),
                new CommandApdu[] {
                        buildSelectApdu(PPSE_AID, true),
                        buildSelectApdu(MC_AID, true),
                        buildCommandApdu("80CA01F000", true)
                });

        RESPONSE_APDUS_BY_SERVICE.put(
                PrefixPaymentService1.class.getName(),
                new String[] {"F1239000", "F4569000", "F789FFAABB9000"});

        COMMAND_APDUS_BY_SERVICE.put(
                PrefixPaymentService2.class.getName(),
                new CommandApdu[] {
                        buildSelectApdu(PPSE_AID, true),
                        buildSelectApdu(MC_AID, true),
                        buildCommandApdu("80CA02F000", true),
                        buildSelectApdu("F0000000FFFFFFFFFFFFFFFFFFFFFFFF", true),
                        buildSelectApdu("F000000000", true)
                });

        RESPONSE_APDUS_BY_SERVICE.put(
                PrefixPaymentService2.class.getName(),
                new String[] {
                        "FAAA9000", "FBBB9000", "F789FFCCDD9000", "FFBAFEBECA", "F0BABEFECA"
                });

        COMMAND_APDUS_BY_SERVICE.put(
                OffHostService.class.getName(),
                new CommandApdu[]{
                        buildSelectApdu(SE_AID_1, true),
                        buildCommandApdu("80CA9F7F00", true),
                        buildSelectApdu(SE_AID_2, true),
                        buildCommandApdu("80CA9F7F00", true)
                });
        RESPONSE_APDUS_BY_SERVICE.put(
                OffHostService.class.getName(),
                new String[] {"*", "*", "*", "*"}
        );
        COMMAND_APDUS_BY_SERVICE.put(
                TransportService2.class.getName(),
                new CommandApdu[] {
                        buildSelectApdu(TRANSPORT_AID, true), buildCommandApdu("80CA01E100", true)
                });
        RESPONSE_APDUS_BY_SERVICE.put(
                TransportService2.class.getName(), new String[] {"81CA9000", "7483624748FEFE9000"});

        COMMAND_APDUS_BY_SERVICE.put(
                AccessService.class.getName(),
                new CommandApdu[] {
                        buildSelectApdu(ACCESS_AID, true), buildCommandApdu("80CA01F000", true)
                });
        RESPONSE_APDUS_BY_SERVICE.put(
                AccessService.class.getName(), new String[] {"123456789000", "1481148114819000"});

        COMMAND_APDUS_BY_SERVICE.put(
                PrefixTransportService1.class.getName(),
                new CommandApdu[] {
                        buildSelectApdu(TRANSPORT_PREFIX_AID + "FFFF", true),
                        buildSelectApdu(TRANSPORT_PREFIX_AID + "FFAA", true),
                        buildSelectApdu(TRANSPORT_PREFIX_AID + "FFAABBCCDDEEFF", true),
                        buildCommandApdu("80CA01FFAA", true)
                });
        RESPONSE_APDUS_BY_SERVICE.put(
                PrefixTransportService1.class.getName(),
                new String[] {
                        "25929000", "FFEF25929000", "FFDFFFAABB25929000", "FFDFFFAACC25929000"
                });

        COMMAND_APDUS_BY_SERVICE.put(
                PrefixTransportService2.class.getName(),
                new CommandApdu[] {
                        buildSelectApdu(TRANSPORT_PREFIX_AID + "FFFF", true),
                        buildSelectApdu(TRANSPORT_PREFIX_AID + "FFAA", true),
                        buildSelectApdu(TRANSPORT_PREFIX_AID + "FFAABBCCDDEEFF", true),
                        buildCommandApdu("80CA01FFBB", true)
                });
        RESPONSE_APDUS_BY_SERVICE.put(
                PrefixTransportService2.class.getName(),
                new String[] {
                        "36039000", "FFBB25929000", "FFDFFFBBBB25929000", "FFDFFFBBCC25929000"
                });

        COMMAND_APDUS_BY_SERVICE.put(
                PrefixAccessService.class.getName(),
                new CommandApdu[] {
                        buildSelectApdu(ACCESS_PREFIX_AID + "FFFF", true),
                        buildSelectApdu(ACCESS_PREFIX_AID + "FFAA", true),
                        buildSelectApdu(ACCESS_PREFIX_AID + "FFAABBCCDDEEFF", true),
                        buildCommandApdu("80CA010000010203", true)
                });
        RESPONSE_APDUS_BY_SERVICE.put(
                PrefixAccessService.class.getName(),
                new String[] {
                        "FAFE9000", "FAFE25929000", "FAFEAABB25929000", "FAFEFFAACC25929000"
                });

        COMMAND_APDUS_BY_SERVICE.put(
                ThroughputService.class.getName(),
                new CommandApdu[] {
                        buildSelectApdu("F0010203040607FF", true),
                        buildCommandApdu("80CA010100", true),
                        buildCommandApdu("80CA010200", true),
                        buildCommandApdu("80CA010300", true),
                        buildCommandApdu("80CA010400", true),
                        buildCommandApdu("80CA010500", true),
                        buildCommandApdu("80CA010600", true),
                        buildCommandApdu("80CA010700", true),
                        buildCommandApdu("80CA010800", true),
                        buildCommandApdu("80CA010900", true),
                        buildCommandApdu("80CA010A00", true),
                        buildCommandApdu("80CA010B00", true),
                        buildCommandApdu("80CA010C00", true),
                        buildCommandApdu("80CA010D00", true),
                        buildCommandApdu("80CA010E00", true),
                        buildCommandApdu("80CA010F00", true),
                });

        RESPONSE_APDUS_BY_SERVICE.put(
                ThroughputService.class.getName(),
                new String[] {
                        "9000",
                        "0000FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF9000",
                        "0001FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF9000",
                        "0002FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF9000",
                        "0003FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF9000",
                        "0004FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF9000",
                        "0005FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF9000",
                        "0006FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF9000",
                        "0007FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF9000",
                        "0008FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF9000",
                        "0009FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF9000",
                        "000AFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF9000",
                        "000BFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF9000",
                        "000CFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF9000",
                        "000DFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF9000",
                        "000EFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF9000",
                });

        CommandApdu[] largeCommandSequence = new CommandApdu[256];
        String[] largeResponseSequence = new String[256];
        for (int i = 0; i < 256; ++i) {
            largeCommandSequence[i] =
                    buildSelectApdu(
                            LARGE_NUM_AIDS_PREFIX
                                    + String.format("%02X", i)
                                    + LARGE_NUM_AIDS_POSTFIX,
                            true);
            largeResponseSequence[i] = "9000" + String.format("%02X", i);
        }

        COMMAND_APDUS_BY_SERVICE.put(LargeNumAidsService.class.getName(), largeCommandSequence);
        RESPONSE_APDUS_BY_SERVICE.put(LargeNumAidsService.class.getName(), largeResponseSequence);

        COMMAND_APDUS_BY_SERVICE.put(
                ScreenOffPaymentService.class.getName(),
                new CommandApdu[] {
                        buildSelectApdu(HceUtils.PPSE_AID, true),
                        buildSelectApdu(HceUtils.MC_AID, true),
                        buildCommandApdu("80CA01F000", true)
                });
        RESPONSE_APDUS_BY_SERVICE.put(
                ScreenOffPaymentService.class.getName(),
                new String[] {"FFFF9000", "FFEF9000", "FFDFFFAABB9000"});

        COMMAND_APDUS_BY_SERVICE.put(
                ScreenOnOnlyOffHostService.class.getName(),
                new CommandApdu[] {
                        buildSelectApdu("A000000476416E64726F696443545340", true),
                });
        RESPONSE_APDUS_BY_SERVICE.put(
                ScreenOnOnlyOffHostService.class.getName(), new String[] {"*"});

        COMMAND_APDUS_BY_SERVICE.put(
                PollingLoopService.class.getName(),
                new CommandApdu[] {buildSelectApdu(HceUtils.ACCESS_AID, true),
                    buildCommandApdu("80CA01F000", true)
                });
        RESPONSE_APDUS_BY_SERVICE.put(
                PollingLoopService.class.getName(),
                new String[] {"123456789000", "1481148114819000"}
        );
    }

    /** Enables specified component */
    public static void enableComponent(PackageManager pm, ComponentName component) {
        pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
    }

    /** Disables specified component */
    public static void disableComponent(PackageManager pm, ComponentName component) {
        pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    /** Converts a byte array to hex string */
    public static String getHexBytes(String header, byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        if (header != null) {
            sb.append(header + ": ");
        }
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString();
    }

    /** Converts a hex string to byte array */
    public static byte[] hexStringToBytes(String s) {
        if (s == null || s.length() == 0) return null;
        int len = s.length();
        if (len % 2 != 0) {
            s = '0' + s;
            len++;
        }
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] =
                    (byte)
                            ((Character.digit(s.charAt(i), 16) << 4)
                                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    /** Builds a command APDU from given string */
    public static CommandApdu buildCommandApdu(String apdu, boolean reachable) {
        return new CommandApdu(apdu, reachable);
    }

    /** Builds a select AID command APDU */
    public static CommandApdu buildSelectApdu(String aid, boolean reachable) {
        String apdu = String.format("00A40400%02X%s", aid.length() / 2, aid);
        return new CommandApdu(apdu, reachable);
    }

    /** Sets default wallet role holder to given package name */
    public static boolean setDefaultWalletRoleHolder(Context context, String packageName) {
        RoleManager roleManager = context.getSystemService(RoleManager.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        try {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation()
                    .adoptShellPermissionIdentity(MANAGE_DEFAULT_APPLICATIONS);
            assert roleManager != null;
            roleManager.setDefaultApplication(
                    RoleManager.ROLE_WALLET,
                    packageName,
                    0,
                    MoreExecutors.directExecutor(),
                    aBoolean -> {
                        result.set(aBoolean);
                        countDownLatch.countDown();
                    });
            countDownLatch.await(3000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation()
                    .dropShellPermissionIdentity();
        }
        return result.get();
    }

    /** Disables secure NFC so that NFC works with screen off */
    public static boolean disableSecureNfc(NfcAdapter adapter) {
        boolean res = false;
        try {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation()
                    .adoptShellPermissionIdentity(WRITE_SECURE_SETTINGS);
            res = adapter.enableSecureNfc(false);
        } finally {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation()
                    .dropShellPermissionIdentity();
        }
        return res;
    }
}
