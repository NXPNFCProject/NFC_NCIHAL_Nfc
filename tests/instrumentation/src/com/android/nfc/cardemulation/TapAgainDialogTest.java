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

package com.android.nfc.cardemulation;

import static android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static com.android.nfc.cardemulation.TapAgainDialog.EXTRA_APDU_SERVICE;
import static com.android.nfc.cardemulation.TapAgainDialog.EXTRA_CATEGORY;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.nfc.cardemulation.ApduServiceInfo;
import android.view.Window;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.nfc.R;

import java.util.ArrayList;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TapAgainDialogTest {
  private static final int ALERT_DIALOG_ID = com.android.internal.R.id.parentPanel;

  @Test
  public void testOnCreate() throws Exception {
    ActivityScenario<TapAgainDialog> scenario = ActivityScenario.launch(getStartIntent());

    assertThat(scenario.getState()).isAtLeast(Lifecycle.State.CREATED);

    scenario.onActivity(activity -> {
      int flags = activity.getWindow().getAttributes().flags;
      assertThat(flags & FLAG_DISMISS_KEYGUARD).isEqualTo(FLAG_DISMISS_KEYGUARD);
    });
  }

  @Test
  public void testOnClick() throws Exception {
    ActivityScenario<TapAgainDialog> scenario = ActivityScenario.launch(getStartIntent());

    assertThat(scenario.getState()).isAtLeast(Lifecycle.State.CREATED);
    scenario.moveToState(Lifecycle.State.RESUMED);
    onView(withId(R.id.tap_again_toolbar)).perform(click());

    onView(withId(ALERT_DIALOG_ID)).check(matches(isDisplayed()));
    onView(withId(R.id.tap_again_toolbar)).check(matches(isDisplayed()));
    onView(withId(R.id.tap_again_appicon)).check(matches(isDisplayed()));
  }

  @Test
  public void testOnDestroy() throws Exception {
    ActivityScenario<TapAgainDialog> scenario = ActivityScenario.launch(getStartIntent());
    scenario.moveToState(Lifecycle.State.DESTROYED);

    assertThat(scenario.getState()).isEqualTo(Lifecycle.State.DESTROYED);
  }

  @Test
  public void testOnStop() throws Exception {
    ActivityScenario<TapAgainDialog> scenario = ActivityScenario.launch(getStartIntent());

    // activity's onPause() and onStop() methods are called
    scenario.moveToState(Lifecycle.State.CREATED);

    assertThat(scenario.getState()).isAtLeast(Lifecycle.State.CREATED);
  }

  private Intent getStartIntent() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    Intent intent = new Intent(context, TapAgainDialog.class);
    intent.putExtra(EXTRA_CATEGORY, "");

    ServiceInfo serviceInfo = new ServiceInfo();
    serviceInfo.packageName = "com.nfc.test";
    serviceInfo.name = "hce_service";
    serviceInfo.applicationInfo = new ApplicationInfo();
    ResolveInfo resolveInfo = new ResolveInfo();
    resolveInfo.serviceInfo = serviceInfo;
    ApduServiceInfo service
        = new ApduServiceInfo(resolveInfo,
        /* onHost = */ false,
        /* description = */ "",
        /* staticAidGroups = */ new ArrayList<>(),
        /* dynamicAidGroups = */ new ArrayList<>(),
        /* requiresUnlock = */ false,
        /* bannerResource = */ 0,
        /* uid = */ 0,
        /* settingsActivityName = */ "",
        /* offHost = */ "",
        /* staticOffHost = */ "");
    intent.putExtra(EXTRA_APDU_SERVICE, service);
    return intent;
  }
}
