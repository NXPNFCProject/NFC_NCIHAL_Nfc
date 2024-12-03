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

import android.content.Context;
import android.nfc.INfcDta;
import android.os.Binder;
import android.os.Process;
import android.os.RemoteException;
import android.text.TextUtils;

import com.android.modules.utils.BasicShellCommandHandler;

import java.io.PrintWriter;

/**
 * Interprets and executes 'adb shell cmd nfc [args]'.
 *
 * To add new commands:
 * - onCommand: Add a case "<command>" execute. Return a 0
 *   if command executed successfully.
 * - onHelp: add a description string.
 *
 * Permissions: currently root permission is required for some commands. Others will
 * enforce the corresponding API permissions.
 */
public class NfcShellCommand extends BasicShellCommandHandler {
    private static final int DISABLE_POLLING_FLAGS = 0x1000;
    private static final int ENABLE_POLLING_FLAGS = 0x0000;

    // These don't require root access. However, these do perform permission checks in the
    // corresponding binder methods in mNfcService.mNfcAdapter.
    // Note: Any time you invoke a method from an internal class, consider making it privileged
    // since these shell commands are available on production builds, we don't want apps to use
    // this command to bypass security restrictions. mNfcService.mNfcAdapter binder
    // methods already enforce permissions of the invoking shell (non-rooted shell has limited
    // set of privileges).
    private static final String[] NON_PRIVILEGED_COMMANDS = {
            "help",
            "disable-nfc",
            "enable-nfc",
            "status",
    };
    private final NfcService mNfcService;
    private final Context mContext;

    NfcShellCommand(NfcService nfcService, Context context) {
        mNfcService = nfcService;
        mContext = context;
    }

    @Override
    public int onCommand(String cmd) {
        // Treat no command as help command.
        if (cmd == null || cmd.equals("")) {
            cmd = "help";
        }
        // Explicit exclusion from root permission
        if (ArrayUtils.indexOf(NON_PRIVILEGED_COMMANDS, cmd) == -1) {
            final int uid = Binder.getCallingUid();
            if (uid != Process.ROOT_UID) {
                throw new SecurityException(
                        "Uid " + uid + " does not have access to " + cmd + " nfc command "
                                + "(or such command doesn't exist)");
            }
        }

        final PrintWriter pw = getOutPrintWriter();
        try {
            switch (cmd) {
                case "status":
                    printStatus(pw);
                    return 0;
                case "disable-nfc":
                    String stringSaveState = getNextArg();
                    boolean saveState = false;
                    if (TextUtils.equals(stringSaveState, "[persist]")) {
                        saveState = true;
                    }
                    mNfcService.mNfcAdapter.disable(saveState, mContext.getPackageName());
                    return 0;
                case "enable-nfc":
                    mNfcService.mNfcAdapter.enable(mContext.getPackageName());
                    return 0;
                case "set-reader-mode":
                    boolean enable_polling =
                            getNextArgRequiredTrueOrFalse("enable-polling", "disable-polling");
                    int flags = enable_polling ? ENABLE_POLLING_FLAGS : DISABLE_POLLING_FLAGS;
                    mNfcService.mNfcAdapter.setReaderMode(
                        new Binder(), null, flags, null, mContext.getPackageName());
                    return 0;
                case "set-observe-mode":
                    boolean enable = getNextArgRequiredTrueOrFalse("enable", "disable");
                    mNfcService.mNfcAdapter.setObserveMode(enable, mContext.getPackageName());
                    return 0;
                case "set-controller-always-on":
                    int mode = Integer.parseInt(getNextArgRequired());
                    mNfcService.mNfcAdapter.setControllerAlwaysOn(mode);
                    return 0;
                case "set-discovery-tech":
                    int pollTech = Integer.parseInt(getNextArg());
                    int listenTech = Integer.parseInt(getNextArg());
                    mNfcService.mNfcAdapter.updateDiscoveryTechnology(
                            new Binder(), pollTech, listenTech, mContext.getPackageName());
                    return 0;
                case "configure-dta":
                    boolean enableDta = getNextArgRequiredTrueOrFalse("enable", "disable");
                    configureDta(enableDta);
                    return 0;
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (IllegalArgumentException e) {
            pw.println("Invalid args for " + cmd + ": ");
            e.printStackTrace(pw);
            return -1;
        } catch (Exception e) {
            pw.println("Exception while executing nfc shell command" + cmd + ": ");
            e.printStackTrace(pw);
            return -1;
        }
    }

    private void configureDta(boolean enable) {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("  configure-dta");
        try {
            INfcDta dtaService =
                    mNfcService.mNfcAdapter.getNfcDtaInterface(mContext.getPackageName());
            if (enable) {
                pw.println("  enableDta()");
                dtaService.enableDta();
            } else {
                pw.println("  disableDta()");
                dtaService.disableDta();
            }
        } catch (Exception e) {
            pw.println("Exception while executing nfc shell command configureDta():");
            e.printStackTrace(pw);
        }
    }

    private static boolean argTrueOrFalse(String arg, String trueString, String falseString) {
        if (trueString.equals(arg)) {
            return true;
        } else if (falseString.equals(arg)) {
            return false;
        } else {
            throw new IllegalArgumentException("Expected '" + trueString + "' or '" + falseString
                    + "' as next arg but got '" + arg + "'");
        }

    }

    private boolean getNextArgRequiredTrueOrFalse(String trueString, String falseString)
            throws IllegalArgumentException {
        String nextArg = getNextArgRequired();
        return argTrueOrFalse(nextArg, trueString, falseString);
    }

    private void printStatus(PrintWriter pw) throws RemoteException {
        pw.println("Nfc is " + (mNfcService.isNfcEnabled() ? "enabled" : "disabled"));
    }

    private void onHelpNonPrivileged(PrintWriter pw) {
        pw.println("  status");
        pw.println("    Gets status of UWB stack");
        pw.println("  enable-nfc");
        pw.println("    Toggle NFC on");
        pw.println("  disable-nfc [persist]");
        pw.println("    Toggle NFC off (optionally make it persistent)");
    }

    private void onHelpPrivileged(PrintWriter pw) {
        pw.println("  set-observe-mode enable|disable");
        pw.println("    Enable or disable observe mode.");
        pw.println("  set-reader-mode enable-polling|disable-polling");
        pw.println("    Enable or reader mode polling");
        pw.println("  set-controller-always-on <mode>");
        pw.println("    Enable or disable controller always on");
        pw.println("  set-discovery-tech poll-mask|listen-mask");
        pw.println("    set discovery technology for polling and listening.");
        pw.println("  configure-dta enable|disable");
        pw.println("    Enable or disable DTA");
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("NFC (Near-field communication) commands:");
        pw.println("  help or -h");
        pw.println("    Print this help text.");
        onHelpNonPrivileged(pw);
        if (Binder.getCallingUid() == Process.ROOT_UID) {
            onHelpPrivileged(pw);
        }
        pw.println();
    }
}
