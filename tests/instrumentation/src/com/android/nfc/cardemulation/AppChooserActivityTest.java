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

import static android.app.Activity.RESULT_CANCELED;
import static android.nfc.cardemulation.CardEmulation.CATEGORY_PAYMENT;
import static android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
import static android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.android.nfc.cardemulation.AppChooserActivity.EXTRA_CATEGORY;
import static com.android.nfc.cardemulation.AppChooserActivity.EXTRA_APDU_SERVICES;
import static com.android.nfc.cardemulation.AppChooserActivity.EXTRA_FAILED_COMPONENT;
import static com.google.common.truth.Truth.assertThat;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.nfc.cardemulation.ApduServiceInfo;
import android.widget.ListView;
import android.view.Window;
import android.view.View;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.matcher.ViewMatchers;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.nfc.R;

import java.lang.IllegalStateException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.hamcrest.Matcher;

import org.junit.Rule;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AppChooserActivityTest {
  private static final String UNKNOWN_LABEL = "unknown";
  private Context context;

  @Before
  public void setUp() throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
  }

  @Test
  public void testNoFailedComponentAndNoAlternatives() throws Exception {
    ActivityScenario<AppChooserActivity> scenario
        = ActivityScenario.launch(getIntent(/*isPayment = */ true,
                                            /* withFailedComponent = */ false,
                                            /* withServices = */ false));

    assertThat(scenario.getState()).isEqualTo(Lifecycle.State.DESTROYED);
  }

  @Test
  public void testExistingFailedComponentAndNoAlternatives() throws Exception {
    ActivityScenario<AppChooserActivity> scenario
        = ActivityScenario.launch(getIntent(/*isPayment = */ true,
                                            /* withFailedComponent = */ true,
                                            /* withServices = */ false));

    assertThat(scenario.getState()).isAtLeast(Lifecycle.State.CREATED);
    String expectedText
        = String.format(context.getString(R.string.transaction_failure), UNKNOWN_LABEL);
    onView(withId(R.id.appchooser_text)).check(matches(withText(expectedText)));
    scenario.onActivity(activity -> {
      int flags = activity.getWindow().getAttributes().flags;
      assertThat(flags & FLAG_BLUR_BEHIND).isEqualTo(FLAG_BLUR_BEHIND);
      assertThat(flags & FLAG_DISMISS_KEYGUARD).isEqualTo(FLAG_DISMISS_KEYGUARD);
    });
  }

  @Test
  public void testNonPayment() throws Exception {
    ActivityScenario<AppChooserActivity> scenario
        = ActivityScenario.launch(getIntent(/*isPayment = */ false,
                                            /* withFailedComponent = */ true,
                                            /* withServices = */ true));

    scenario.onActivity(activity -> {
      ListView listView = (ListView) activity.findViewById(R.id.resolver_list);
      assertThat(listView.getDividerHeight()).isEqualTo(-1);
      assertThat(listView.getPaddingEnd()).isEqualTo(0);
      assertThat(listView.getPaddingLeft()).isEqualTo(0);
      assertThat(listView.getPaddingRight()).isEqualTo(0);
      assertThat(listView.getPaddingStart()).isEqualTo(0);
    });
  }

  @Test
  public void testExistingFailedComponentAndExistingAlternatives() throws Exception {
    ActivityScenario<AppChooserActivity> scenario
        = ActivityScenario.launch(getIntent(/*isPayment = */ true,
                                                    /* withFailedComponent = */ true,
                                                    /* withServices = */ true));

    assertThat(scenario.getState()).isAtLeast(Lifecycle.State.CREATED);
    String expectedText
        = String.format(context.getString(R.string.could_not_use_app), UNKNOWN_LABEL);
    onView(withId(R.id.appchooser_text)).check(matches(withText(expectedText)));
    scenario.onActivity(activity -> {
      int flags = activity.getWindow().getAttributes().flags;
      assertThat(flags & FLAG_BLUR_BEHIND).isEqualTo(FLAG_BLUR_BEHIND);
      assertThat(flags & FLAG_DISMISS_KEYGUARD).isEqualTo(FLAG_DISMISS_KEYGUARD);

      ListView listView = (ListView) activity.findViewById(R.id.resolver_list);
      assertThat(listView.getDivider()).isNotNull();
      assertThat((int) listView.getDividerHeight())
          .isEqualTo((int) (context.getResources().getDisplayMetrics().density * 16));
      assertThat(listView.getAdapter()).isNotNull();
    });

    // Test that onItemClick() does not throw an Exception
    onView(withId(R.id.resolver_list)).perform(customClick());
  }

  @Test
  public void testNoFailedComponentAndExistingAlternatives() throws Exception {
    ActivityScenario<AppChooserActivity> scenario
        = ActivityScenario.launch(getIntent(/*isPayment = */ true,
                                                    /* withFailedComponent = */ false,
                                                    /* withServices = */ true));

    assertThat(scenario.getState()).isAtLeast(Lifecycle.State.CREATED);
    scenario.moveToState(Lifecycle.State.RESUMED);
    String expectedText = context.getString(R.string.appchooser_description);
    onView(withId(R.id.appchooser_text)).check(matches(withText(expectedText)));
    scenario.onActivity(activity -> {
      int flags = activity.getWindow().getAttributes().flags;
      assertThat(flags & FLAG_BLUR_BEHIND).isEqualTo(FLAG_BLUR_BEHIND);
      assertThat(flags & FLAG_DISMISS_KEYGUARD).isEqualTo(FLAG_DISMISS_KEYGUARD);

      ListView listView = (ListView) activity.findViewById(R.id.resolver_list);
      assertThat(listView.getDivider()).isNotNull();
      assertThat((int) listView.getDividerHeight())
          .isEqualTo((int) (context.getResources().getDisplayMetrics().density * 16));
      assertThat(listView.getAdapter()).isNotNull();
    });

    // Test that onItemClick() does not throw an Exception
    onView(withId(R.id.resolver_list)).perform(customClick());
  }

  private Intent getIntent(boolean isPayment, boolean withFailedComponent, boolean withServices) {
    Intent intent = new Intent(context, AppChooserActivity.class);
    if (isPayment) {
      intent.putExtra(EXTRA_CATEGORY, CATEGORY_PAYMENT);
    } else {
      intent.putExtra(EXTRA_CATEGORY, "");
    }

    ArrayList<ApduServiceInfo> services = new ArrayList<ApduServiceInfo>();
    if (withServices) {
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
      services.add(service);
    }
    intent.putParcelableArrayListExtra(EXTRA_APDU_SERVICES, services);

    if (withFailedComponent) {
      ComponentName failedComponent
          = new ComponentName("com.android.test.walletroleholder",
                              "com.android.test.walletroleholder.WalletRoleHolderApduService");
      intent.putExtra(EXTRA_FAILED_COMPONENT, failedComponent);
    }
    return intent;
  }

  // Bypasses the view.getGlobalVisibleRect() requirement on the default click() action
  private ViewAction customClick() {
    return new ViewAction() {
      @Override
      public Matcher<View> getConstraints() {
        return ViewMatchers.isEnabled();
      }

      @Override
      public String getDescription() {
        return "";
      }

      @Override
      public void perform(UiController uiController, View view) {
        view.performClick();
      }
    };
  }
}
