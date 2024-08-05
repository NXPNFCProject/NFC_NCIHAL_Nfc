package com.android.nfc.emulator;

import android.content.ComponentName;
import android.content.Intent;
import android.nfc.NfcAdapter.ReaderCallback;
import android.nfc.Tag;
import android.os.Bundle;
import android.util.Log;

import com.android.nfc.service.PollingLoopService;

public class PN532Activity extends BaseEmulatorActivity implements ReaderCallback {
    public static final String ACTION_TAG_DISCOVERED = PACKAGE_NAME + ".TAG_DISCOVERED";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupServices(PollingLoopService.COMPONENT);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mCardEmulation.setPreferredService(this, PollingLoopService.COMPONENT);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mCardEmulation.unsetPreferredService(this);
    }

    @Override
    public ComponentName getPreferredServiceComponent() {
        return PollingLoopService.COMPONENT;
    }


    public void enableReaderMode(int flags) {
        Log.d(TAG, "enableReaderMode: " + flags);
        mAdapter.enableReaderMode(this, this, flags, null);
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        Log.d(TAG, "onTagDiscovered");
        Intent intent = new Intent(ACTION_TAG_DISCOVERED);
        sendBroadcast(intent);
    }

}
