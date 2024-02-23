#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <statslog_nfc.h>

#include "NfcTag.h"
#include "nfc_api.h"

class MockNfcStatsUtil : public NfcStatsUtil {
 public:
  MOCK_METHOD(void, writeNfcStatsTagTypeOccurred, (int));
};

class NfcTagTest : public ::testing::Test {
 protected:
  NfcTag mNfcTag;

 public:
  void setNfcStatsUtil(NfcStatsUtil* nfcStatsUtil) {
    mNfcTag.mNfcStatsUtil = nfcStatsUtil;
  }
};

TEST_F(NfcTagTest, NfcTagTypeOccurredType5) {
  MockNfcStatsUtil* mockUtil = new MockNfcStatsUtil();

  EXPECT_CALL(*mockUtil,
              writeNfcStatsTagTypeOccurred(
                  nfc::stats::NFC_TAG_TYPE_OCCURRED__TYPE__TAG_TYPE_5))
      .Times(1);

  setNfcStatsUtil(mockUtil);

  tNFA_ACTIVATED mockActivated;
  mockActivated.activate_ntf.rf_disc_id = 1;
  mockActivated.activate_ntf.protocol = NFC_PROTOCOL_T5T;
  mockActivated.activate_ntf.rf_tech_param.mode = NCI_DISCOVERY_TYPE_POLL_V;
  mockActivated.activate_ntf.intf_param.type = NCI_INTERFACE_FRAME;

  tNFA_CONN_EVT_DATA mockData;
  mockData.activated = mockActivated;

  mNfcTag.connectionEventHandler(NFA_ACTIVATED_EVT, &mockData);

  delete mockUtil;
}
