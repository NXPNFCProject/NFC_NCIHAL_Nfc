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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.nfc.cardemulation.ApduServiceInfo;
import android.view.LayoutInflater;
import android.view.View;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.nfc.tests.unit.R;

import java.util.ArrayList;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ListAdapterTest {

  private Context context;
  private Context mockContext;
  private View viewInput;
  @Mock
  private ApduServiceInfo mockService;
  @Mock
  private LayoutInflater mockInflater;

  @Rule
  public final ActivityTestRule<AppChooserActivity> rule
      = new ActivityTestRule<>(AppChooserActivity.class);

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    // Set up mocks for onView()
    viewInput
        = spy(LayoutInflater.from(context).inflate(R.layout.activity_layout_test, null, false));
    AppChooserActivity.ViewHolder viewHolder = new AppChooserActivity.ViewHolder(viewInput);
    viewHolder.text = viewInput.findViewById(R.id.applabel);
    viewHolder.icon = viewInput.findViewById(R.id.appicon);
    viewHolder.banner = viewInput.findViewById(R.id.banner);
    doReturn(viewHolder).when(viewInput).getTag();
    when(mockInflater.inflate(anyInt(), any(), anyBoolean())).thenReturn(viewInput);

    // Set up mockService
    when(mockService.getDescription()).thenReturn("");
    when(mockService.loadIcon(any(PackageManager.class))).thenReturn(null);
    when(mockService.loadBanner(any(PackageManager.class))).thenReturn(null);
    when(mockService.getUid()).thenReturn(0);

    mockContext = new ContextWrapper(context) {
      @Override
      public Object getSystemService(String name) {
        return mockInflater;
      }
    };
  }

  @Test
  public void testListAdapterConstructor() throws Throwable {
    rule.runOnUiThread(() -> {
      AppChooserActivity.ListAdapter adapter
          = rule.getActivity().new ListAdapter(mockContext, getMockServiceList());

      assertThat(adapter.getCount()).isEqualTo(1);
      assertThat(adapter.getItem(0)).isNotNull();
    });
  }

  @Test
  public void testListAdapterGetViewWithNullConvertView() throws Throwable {
    rule.runOnUiThread(() -> {
      AppChooserActivity.ListAdapter adapter
          = rule.getActivity().new ListAdapter(mockContext, getMockServiceList());
      View viewResult = adapter.getView(0, null, null);

      assertThat(viewResult).isEqualTo(viewInput);
      AppChooserActivity.ViewHolder viewHolderResult = (AppChooserActivity.ViewHolder) viewResult.getTag();
      assertThat(viewHolderResult).isNotNull();
      assertThat(viewHolderResult.icon).isNotNull();
      assertThat(viewHolderResult.text).isNotNull();
    });
  }

  @Test
  public void testListAdapterGetViewWithNonNullConvertView() throws Throwable {
    rule.runOnUiThread(() -> {
      AppChooserActivity.ListAdapter adapter
          = rule.getActivity().new ListAdapter(mockContext, getMockServiceList());
      View viewResult = adapter.getView(0, viewInput, null);

      assertThat(viewResult).isEqualTo(viewInput);
      AppChooserActivity.ViewHolder viewHolderResult
          = (AppChooserActivity.ViewHolder) viewResult.getTag();
      assertThat(viewHolderResult).isNotNull();
      assertThat(viewHolderResult.icon).isNotNull();
      assertThat(viewHolderResult.text).isNotNull();
    });
  }

  private ArrayList<ApduServiceInfo> getMockServiceList() {
    ArrayList<ApduServiceInfo> serviceList = new ArrayList<>();
    serviceList.add(mockService);
    return serviceList;
  }
}
