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

import static com.android.nfc.cardemulation.HostNfcFEmulationManager.STATE_IDLE;
import static com.android.nfc.cardemulation.HostNfcFEmulationManager.STATE_W4_SERVICE;
import static com.android.nfc.cardemulation.HostNfcFEmulationManager.STATE_XFER;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.nfc.cardemulation.HostNfcFService;
import android.nfc.cardemulation.NfcFServiceInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.UserHandle;
import android.util.proto.ProtoOutputStream;

import androidx.test.runner.AndroidJUnit4;

import com.android.nfc.NfcService;

import java.io.PrintWriter;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;

@RunWith(AndroidJUnit4.class)
public class HostNfcFEmulationManagerTest {

  private HostNfcFEmulationManager manager;

  @Mock
  private RegisteredT3tIdentifiersCache mockIdentifiersCache;
  @Mock
  private Messenger mockActiveService;
  @Mock
  private Context mockContext;
  @Mock
  private IBinder mockIBinder;
  @Mock
  private PrintWriter mockPrintWriter;
  @Mock
  private ProtoOutputStream mockProto;
  @Mock
  private NfcFServiceInfo mockResolvedService;

  @Captor
  ArgumentCaptor<Message> msgCaptor;
  @Captor
  ArgumentCaptor<Intent> intentCaptor;
  @Captor
  ArgumentCaptor<UserHandle> userHandleCaptor;
  @Captor
  ArgumentCaptor<ServiceConnection> serviceConnectionCaptor;
  @Captor
  ArgumentCaptor<Integer> flagsCaptor;

  private static final ComponentName testService = new ComponentName(
      "com.android.test.walletroleholder",
      "com.android.test.walletroleholder.WalletRoleHolderApduService");
  private static final byte[] DATA = new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
  private static final int USER_ID = 2;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    when(mockIdentifiersCache.resolveNfcid2(anyString())).thenReturn(null);
    when(mockActiveService.getBinder()).thenReturn(mockIBinder);
    when(mockIdentifiersCache.resolveNfcid2(anyString())).thenReturn(mockResolvedService);
    when(mockResolvedService.getUid()).thenReturn(USER_ID);
    doNothing().when(mockPrintWriter).println(anyString());
    doNothing().when(mockProto).write(anyLong(), anyString());
    doNothing().when(mockProto).end(anyLong());
  }

  @Test
  public void testConstructor() {
    Looper.prepare();

    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);

    assertThat(manager.mContext).isEqualTo(mockContext);
    assertThat(manager.mLock).isNotNull();
    assertThat(manager.mEnabledFgServiceName).isNull();
    assertThat(manager.mT3tIdentifiersCache).isEqualTo(mockIdentifiersCache);
    assertThat(manager.mState).isEqualTo(STATE_IDLE);
  }

  @Test
  public void testOnEnabledForegroundNfcFServiceChangedWithNonNullService() throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);

    manager.onEnabledForegroundNfcFServiceChanged(USER_ID, testService);

    assertThat(manager.mEnabledFgServiceUserId).isEqualTo(USER_ID);
    assertThat(manager.mEnabledFgServiceName).isEqualTo(testService);
  }

  @Test
  public void testOnEnabledForegroundNfcFServiceChangedWithNullService() throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);
    manager.mActiveService = mockActiveService;
    manager.mServiceBound = true;

    manager.onEnabledForegroundNfcFServiceChanged(USER_ID, /* service = */ null);

    verify(mockActiveService).send(any(Message.class));
    verify(mockContext).unbindService(any(ServiceConnection.class));
    assertThat(manager.mEnabledFgServiceUserId).isEqualTo(USER_ID);
    assertThat(manager.mEnabledFgServiceName).isNull();
    assertThat(manager.mServiceBound).isFalse();
    assertThat(manager.mService).isNull();
    assertThat(manager.mServiceName).isNull();
    assertThat(manager.mServiceUserId).isEqualTo(-1);
  }

  @Test
  public void testOnHostEmulationDataWithNullDataAndNoActiveService_ReturnsEarly()
      throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);
    manager.mActiveServiceName = null;

    manager.onHostEmulationData(/* data = */ null);

    assertThat(manager.mState).isEqualTo(STATE_IDLE);
  }

  @Test
  public void testOnHostEmulationDataWithDisabledResolvedServiceFromActiveService_ReturnsEarly()
      throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);
    manager.mActiveServiceName = testService;
    manager.mEnabledFgServiceName = null;

    manager.onHostEmulationData(/* data = */ null);

    assertThat(manager.mState).isEqualTo(STATE_IDLE);
  }

  @Test
  public void testOnHostEmulationDataWithEnabledResolvedServiceFromCache_BindToService()
      throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);
    manager.mActiveServiceName = testService;
    manager.mEnabledFgServiceName = testService;
    manager.mServiceBound = true;
    manager.mServiceName = testService;
    manager.mServiceUserId = 0;
    manager.mService = mockActiveService;

    manager.onHostEmulationData(DATA);

    verify(mockActiveService).send(any(Message.class));
    assertThat(manager.mState).isEqualTo(STATE_XFER);
  }

  @Test
  public void testOnHostEmulationDataWithValidExistingService_BindToService() throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);
    manager.mActiveServiceName = testService;
    manager.mEnabledFgServiceName = testService;
    manager.mEnabledFgServiceUserId = USER_ID;
    manager.mServiceBound = true;
    manager.mServiceName = testService;
    manager.mServiceUserId = USER_ID;
    manager.mService = mockActiveService;

    manager.onHostEmulationData(/* data = */ null);

    verify(mockActiveService).send(any(Message.class));
    assertThat(manager.mState).isEqualTo(STATE_XFER);
  }

  @Test
  public void testOnHostEmulationDataWithInvalidExistingService_WaitingForNewService()
      throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);
    manager.mActiveServiceName = testService;
    manager.mEnabledFgServiceName = testService;

    manager.onHostEmulationData(DATA);

    assertThat(manager.mState).isEqualTo(STATE_W4_SERVICE);
    assertThat(manager.mPendingPacket).isEqualTo(DATA);
  }

  @Test
  public void testOnHostEmulationDataWithXFERState_SendRegularPacketData() throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);
    manager.mActiveServiceName = testService;
    manager.mEnabledFgServiceName = testService;
    manager.mState = STATE_XFER;
    manager.mActiveService = mockActiveService;

    manager.onHostEmulationData(DATA);

    verify(mockActiveService).send(any(Message.class));
  }

  @Test
  public void testOnHostEmulationDataWithW4ServiceState_DoNothing() throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);
    manager.mActiveServiceName = testService;
    manager.mEnabledFgServiceName = testService;
    manager.mState = STATE_W4_SERVICE;

    manager.onHostEmulationData(DATA);

    assertThat(manager.mState).isEqualTo(STATE_W4_SERVICE);
  }

  @Test
  public void testOnHostEmulationDeactivated() throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);
    manager.mActiveService = mockActiveService;

    manager.onHostEmulationDeactivated();

    verify(mockActiveService).send(any(Message.class));
    assertThat(manager.mActiveService).isNull();
    assertThat(manager.mActiveServiceName).isNull();
    assertThat(manager.mState).isEqualTo(STATE_IDLE);
  }

  @Test
  public void testOnNfcDisabled() throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);
    manager.mActiveService = mockActiveService;

    manager.onNfcDisabled();

    verify(mockActiveService).send(any(Message.class));
    assertThat(manager.mEnabledFgServiceName).isNull();
    assertThat(manager.mActiveService).isNull();
    assertThat(manager.mActiveServiceName).isNull();
    assertThat(manager.mState).isEqualTo(STATE_IDLE);
  }

  @Test
  public void testOnUserSwitched() throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);
    manager.mActiveService = mockActiveService;

    manager.onUserSwitched();

    verify(mockActiveService).send(any(Message.class));
    assertThat(manager.mEnabledFgServiceName).isNull();
    assertThat(manager.mActiveService).isNull();
    assertThat(manager.mActiveServiceName).isNull();
    assertThat(manager.mState).isEqualTo(STATE_IDLE);
  }

  @Test
  public void testSendDataToServiceLockedWithNewService() throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);

    manager.sendDataToServiceLocked(mockActiveService, DATA);

    verify(mockActiveService).send(msgCaptor.capture());
    Message msg = msgCaptor.getValue();
    assertThat(msg.getTarget()).isNull();
    assertThat(msg.what).isEqualTo(HostNfcFService.MSG_COMMAND_PACKET);
    assertThat(msg.getData().getByteArray("data")).isEqualTo(DATA);
  }

  @Test
  public void testSendDataToServiceLockedWithExistingService() throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);
    manager.mActiveService = mockActiveService;

    manager.sendDataToServiceLocked(mockActiveService, DATA);

    verify(mockActiveService).send(msgCaptor.capture());
    Message msg = msgCaptor.getValue();
    assertThat(msg.getTarget()).isNull();
    assertThat(msg.what).isEqualTo(HostNfcFService.MSG_COMMAND_PACKET);
    assertThat(msg.getData().getByteArray("data")).isEqualTo(DATA);
  }

  @Test
  public void testDeactivateToActiveServiceLockedWithNullActiveService() throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);

    manager.sendDeactivateToActiveServiceLocked(HostNfcFService.DEACTIVATION_LINK_LOSS);

    verify(mockActiveService, times(0)).send(any(Message.class));
  }

  @Test
  public void testDeactivateToActiveServiceLockedWithNonNullActiveService() throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);
    manager.mActiveService = mockActiveService;

    manager.sendDeactivateToActiveServiceLocked(HostNfcFService.DEACTIVATION_LINK_LOSS);

    verify(mockActiveService).send(msgCaptor.capture());
    Message msg = msgCaptor.getValue();
    assertThat(msg.getTarget()).isNull();
    assertThat(msg.what).isEqualTo(HostNfcFService.MSG_DEACTIVATED);
    assertThat(msg.arg1).isEqualTo(HostNfcFService.DEACTIVATION_LINK_LOSS);
  }

  @Test
  public void testBindServiceWithNewService_SuccessfullyBindsToService() throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);
    when(mockContext.bindServiceAsUser(any(Intent.class), any(ServiceConnection.class), anyInt(),
        any(UserHandle.class))).thenReturn(true);

    Messenger result = manager.bindServiceIfNeededLocked(USER_ID, testService);

    verify(mockContext).bindServiceAsUser(intentCaptor.capture(), serviceConnectionCaptor.capture(),
        flagsCaptor.capture(), userHandleCaptor.capture());
    Intent bindIntent = intentCaptor.getValue();
    assertThat(bindIntent.getAction()).isEqualTo(HostNfcFService.SERVICE_INTERFACE);
    assertThat(bindIntent.getComponent()).isEqualTo(testService);
    assertThat(serviceConnectionCaptor.getValue()).isNotNull();
    assertThat(flagsCaptor.getValue()).isEqualTo(Context.BIND_AUTO_CREATE);
    assertThat(userHandleCaptor.getValue()).isEqualTo(UserHandle.of(USER_ID));
    assertThat(manager.mServiceUserId).isEqualTo(USER_ID);
    assertThat(result).isNull();
  }

  @Test
  public void testBindServiceWithNewService_FailsToBindToService() throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);
    when(mockContext.bindServiceAsUser(any(Intent.class), any(ServiceConnection.class), anyInt(),
        any(UserHandle.class))).thenReturn(false);

    Messenger result = manager.bindServiceIfNeededLocked(USER_ID, testService);

    assertThat(manager.mServiceUserId).isEqualTo(0);
    assertThat(result).isNull();
  }

  @Test
  public void testBindServiceWithExistingService_ServiceAlreadyBound() throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);
    manager.mService = mockActiveService;
    manager.mServiceBound = true;
    manager.mServiceName = testService;
    manager.mServiceUserId = USER_ID;

    Messenger result = manager.bindServiceIfNeededLocked(USER_ID, testService);

    assertThat(result).isEqualTo(mockActiveService);
  }

  @Test
  public void testUnbindService() throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);
    manager.mServiceBound = true;

    manager.unbindServiceIfNeededLocked();

    verify(mockContext).unbindService(any(ServiceConnection.class));
    assertThat(manager.mServiceBound).isFalse();
    assertThat(manager.mService).isNull();
    assertThat(manager.mServiceName).isNull();
    assertThat(manager.mServiceUserId).isEqualTo(-1);
  }

  @Test
  public void testFindNfcid2WithNullData() throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);

    String result = manager.findNfcid2(/* data = */ null);

    assertThat(result).isNull();
  }

  @Test
  public void testFindNfcid2WithNonNullData() throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);

    String result = manager.findNfcid2(DATA);

    assertThat(result).isEqualTo("0203040506070809");
  }

  @Test
  public void testServiceConnectionOnConnected() throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);
    manager.mPendingPacket = DATA;
    manager.bindServiceIfNeededLocked(USER_ID, testService);
    verify(mockContext).bindServiceAsUser(any(Intent.class), serviceConnectionCaptor.capture(),
        anyInt(), any(UserHandle.class));
    ServiceConnection connection = serviceConnectionCaptor.getValue();

    connection.onServiceConnected(testService, mockIBinder);

    assertThat(manager.mService).isNotNull();
    assertThat(manager.mServiceBound).isTrue();
    assertThat(manager.mServiceName).isEqualTo(testService);
    assertThat(manager.mState).isEqualTo(STATE_XFER);
    assertThat(manager.mPendingPacket).isNull();
  }

  @Test
  public void testServiceConnectionOnDisconnected() throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);
    manager.bindServiceIfNeededLocked(USER_ID, testService);
    verify(mockContext).bindServiceAsUser(any(Intent.class), serviceConnectionCaptor.capture(),
        anyInt(), any(UserHandle.class));
    ServiceConnection connection = serviceConnectionCaptor.getValue();

    connection.onServiceDisconnected(testService);

    assertThat(manager.mService).isNull();
    assertThat(manager.mServiceBound).isFalse();
    assertThat(manager.mServiceName).isNull();
  }

  @Test
  public void testMessageHandler() throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);
    manager.mActiveService = mockActiveService;
    Message msg = new Message();
    msg.replyTo = mockActiveService;
    msg.what = HostNfcFService.MSG_RESPONSE_PACKET;
    Bundle dataBundle = new Bundle();
    dataBundle.putByteArray("data", new byte[]{});
    msg.setData(dataBundle);
    HostNfcFEmulationManager.MessageHandler messageHandler = manager.new MessageHandler();

    messageHandler.handleMessage(msg);
  }

  @Test
  public void testBytesToString() throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);

    String result = manager.bytesToString(DATA, /* offset = */ 0, DATA.length);

    assertThat(result).isEqualTo("000102030405060708090A0B");
  }

  @Test
  public void testDumpWithBoundService() throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);
    manager.mServiceBound = true;

    manager.dump(/* fd = */ null, mockPrintWriter, /* args = */ null);

    verify(mockPrintWriter, times(2)).println(anyString());
  }

  @Test
  public void testDumpWithoutBoundService() throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);

    manager.dump(/* fd = */ null, mockPrintWriter, /* args = */ null);

    verify(mockPrintWriter).println(anyString());
  }

  @Test
  public void testDumpDebugWithBoundService() throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);
    manager.mServiceBound = true;
    manager.mServiceName = testService;

    manager.dumpDebug(mockProto);

    verify(mockProto, times(2)).write(anyLong(), anyString());
    verify(mockProto, times(1)).end(anyLong());
  }

  @Test
  public void testDumpDebugWithoutBoundService() throws Exception {
    Looper.prepare();
    manager = new HostNfcFEmulationManager(mockContext, mockIdentifiersCache);

    manager.dumpDebug(mockProto);

    verify(mockProto, times(0)).write(anyLong(), anyString());
    verify(mockProto, times(0)).end(anyLong());
  }
}