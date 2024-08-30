/*
 * Copyright (C) 2009 The Android Open Source Project
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

import static android.content.pm.PackageManager.GET_META_DATA;
import static android.content.pm.PackageManager.MATCH_CLONE_PROFILE;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManager.ResolveInfoFlags;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.UserHandle;
import android.sysprop.NfcProperties;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A cache of intent filters registered to receive the TECH_DISCOVERED dispatch.
 */
public class RegisteredComponentCache {
    private static final String TAG = "RegisteredComponentCache";
    private static final boolean DEBUG =
            NfcProperties.debug_enabled().orElse(true);
    private static final boolean VDBG = false; // turn on for local testing.

    final Context mContext;
    final String mAction;
    final String mMetaDataName;
    final AtomicReference<BroadcastReceiver> mReceiver;

    // synchronized on this
    private ArrayList<ComponentInfo> mComponents = new ArrayList<>();

    public RegisteredComponentCache(Context context, String action, String metaDataName) {
        mContext = context;
        mAction = action;
        mMetaDataName = metaDataName;

        generateComponentsList();

        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context1, Intent intent) {
                generateComponentsList();
            }
        };
        mReceiver = new AtomicReference<BroadcastReceiver>(receiver);
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addDataScheme("package");
        mContext.registerReceiverForAllUsers(receiver, intentFilter, null, null);
        // Register for events related to sdcard installation.
        IntentFilter sdFilter = new IntentFilter();
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        mContext.registerReceiverForAllUsers(receiver, sdFilter, null, null);
        // Generate a new list upon switching users as well
        IntentFilter userFilter = new IntentFilter();
        userFilter.addAction(Intent.ACTION_USER_SWITCHED);
        mContext.registerReceiverForAllUsers(receiver, userFilter, null, null);
    }

    public static class ComponentInfo {
        public final ResolveInfo resolveInfo;
        public final String[] techs;

        ComponentInfo(ResolveInfo resolveInfo, String[] techs) {
            this.resolveInfo = resolveInfo;
            this.techs = techs;
        }

        @Override
        public String toString() {
            StringBuilder out = new StringBuilder("ComponentInfo: ");
            out.append(resolveInfo);
            out.append(", techs: ");
            for (String tech : techs) {
                out.append(tech);
                out.append(", ");
            }
            return out.toString();
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof ComponentInfo) {
                ComponentInfo oCI = (ComponentInfo) other;
                return Objects.equals(resolveInfo.activityInfo, oCI.resolveInfo.activityInfo)
                        && Arrays.equals(techs, oCI.techs);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(techs), resolveInfo.activityInfo);
        }
    }

    /**
     * @return a collection of {@link RegisteredComponentCache.ComponentInfo} objects for all
     * registered authenticators.
     */
    public ArrayList<ComponentInfo> getComponents() {
        synchronized (this) {
            // It's safe to return a reference here since mComponents is always replaced and
            // never updated when it changes.
            return mComponents;
        }
    }

    /**
     * Stops the monitoring of package additions, removals and changes.
     */
    public void close() {
        final BroadcastReceiver receiver = mReceiver.getAndSet(null);
        if (receiver != null) {
            mContext.unregisterReceiver(receiver);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        if (mReceiver.get() != null) {
            Log.e(TAG, "RegisteredServicesCache finalized without being closed");
        }
        close();
        super.finalize();
    }

    void dump(ArrayList<ComponentInfo> components) {
        for (ComponentInfo component : components) {
            Log.i(TAG, component.toString());
        }
    }

    void generateComponentsList() {
        PackageManager pm;
        try {
            UserHandle currentUser = UserHandle.of(ActivityManager.getCurrentUser());
            pm = mContext.createPackageContextAsUser("android", 0,
                    currentUser).getPackageManager();
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Could not create user package context");
            return;
        }
        ArrayList<ComponentInfo> components = new ArrayList<ComponentInfo>();
        List<ResolveInfo> resolveInfos = pm.queryIntentActivitiesAsUser(new Intent(mAction),
                ResolveInfoFlags.of(GET_META_DATA
                        | MATCH_CLONE_PROFILE),
                UserHandle.of(ActivityManager.getCurrentUser()));
        for (ResolveInfo resolveInfo : resolveInfos) {
            try {
                parseComponentInfo(pm, resolveInfo, components);
            } catch (XmlPullParserException e) {
                Log.w(TAG, "Unable to load component info " + resolveInfo.toString(), e);
            } catch (IOException e) {
                Log.w(TAG, "Unable to load component info " + resolveInfo.toString(), e);
            }
        }

        if (VDBG) {
            Log.i(TAG, "Components => ");
            dump(components);
        } else {
            // dump only new components added or removed
            ArrayList<ComponentInfo> newComponents = new ArrayList<>(components);
            newComponents.removeAll(mComponents);
            ArrayList<ComponentInfo> removedComponents = new ArrayList<>(mComponents);
            removedComponents.removeAll(components);
            Log.i(TAG, "New Components => ");
            dump(newComponents);
            Log.i(TAG, "Removed Components => ");
            dump(removedComponents);
        }

        synchronized (this) {
            mComponents = components;
        }
    }

    void parseComponentInfo(PackageManager pm, ResolveInfo info,
            ArrayList<ComponentInfo> components) throws XmlPullParserException, IOException {
        ActivityInfo ai = info.activityInfo;

        XmlResourceParser parser = null;
        try {
            parser = ai.loadXmlMetaData(pm, mMetaDataName);
            if (parser == null) {
                throw new XmlPullParserException("No " + mMetaDataName + " meta-data");
            }

            parseTechLists(pm.getResourcesForApplication(ai.applicationInfo), ai.packageName,
                    parser, info, components);
        } catch (NameNotFoundException e) {
            throw new XmlPullParserException("Unable to load resources for " + ai.packageName);
        } finally {
            if (parser != null) parser.close();
        }
    }

    void parseTechLists(Resources res, String packageName, XmlPullParser parser,
            ResolveInfo resolveInfo, ArrayList<ComponentInfo> components)
            throws XmlPullParserException, IOException {
        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.START_TAG) {
            eventType = parser.next();
        }

        ArrayList<String> items = new ArrayList<String>();
        String tagName;
        eventType = parser.next();
        do {
            tagName = parser.getName();
            if (eventType == XmlPullParser.START_TAG && "tech".equals(tagName)) {
                items.add(parser.nextText());
            } else if (eventType == XmlPullParser.END_TAG && "tech-list".equals(tagName)) {
                int size = items.size();
                if (size > 0) {
                    String[] techs = new String[size];
                    techs = items.toArray(techs);
                    items.clear();
                    components.add(new ComponentInfo(resolveInfo, techs));
                }
            }
            eventType = parser.next();
        } while (eventType != XmlPullParser.END_DOCUMENT);
    }
}
