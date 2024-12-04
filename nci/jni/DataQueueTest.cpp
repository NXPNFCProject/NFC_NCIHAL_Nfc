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

#include <gtest/gtest.h>

#include "DataQueue.h"

// Define a test fixture class
class DataQueueTest : public ::testing::Test {
 protected:
  DataQueue queue;
};

// Test isEmpty() when the queue is empty
TEST_F(DataQueueTest, IsEmptyInitially) { ASSERT_TRUE(queue.isEmpty()); }

// Test enqueue() and dequeue() with a single element
TEST_F(DataQueueTest, EnqueueDequeueOneElement) {
  uint8_t data[] = {1, 2, 3};
  ASSERT_TRUE(queue.enqueue(data, sizeof(data)));
  ASSERT_FALSE(queue.isEmpty());

  uint8_t buffer[10];
  uint16_t actualLen;
  ASSERT_TRUE(queue.dequeue(buffer, sizeof(buffer), actualLen));
  ASSERT_EQ(actualLen, sizeof(data));
  ASSERT_EQ(memcmp(buffer, data, sizeof(data)), 0);
  ASSERT_TRUE(queue.isEmpty());
}

// Test enqueue() and dequeue() with multiple elements
TEST_F(DataQueueTest, EnqueueDequeueMultipleElements) {
  uint8_t data1[] = {1, 2, 3};
  uint8_t data2[] = {4, 5, 6, 7};
  ASSERT_TRUE(queue.enqueue(data1, sizeof(data1)));
  ASSERT_TRUE(queue.enqueue(data2, sizeof(data2)));

  uint8_t buffer[10];
  uint16_t actualLen;
  ASSERT_TRUE(queue.dequeue(buffer, sizeof(buffer), actualLen));
  ASSERT_EQ(actualLen, sizeof(data1));
  ASSERT_EQ(memcmp(buffer, data1, sizeof(data1)), 0);

  ASSERT_TRUE(queue.dequeue(buffer, sizeof(buffer), actualLen));
  ASSERT_EQ(actualLen, sizeof(data2));
  ASSERT_EQ(memcmp(buffer, data2, sizeof(data2)), 0);
  ASSERT_TRUE(queue.isEmpty());
}

// Test dequeue() with a buffer smaller than the data size
TEST_F(DataQueueTest, DequeuePartial) {
  uint8_t data[] = {1, 2, 3, 4, 5};
  ASSERT_TRUE(queue.enqueue(data, sizeof(data)));

  uint8_t buffer[3];  // Smaller buffer
  uint16_t actualLen;
  ASSERT_TRUE(queue.dequeue(buffer, sizeof(buffer), actualLen));
  ASSERT_EQ(actualLen, sizeof(buffer));
  ASSERT_EQ(memcmp(buffer, data, sizeof(buffer)), 0);

  ASSERT_TRUE(queue.dequeue(buffer, sizeof(buffer), actualLen));
  ASSERT_EQ(actualLen, 2);  // Remaining 2 bytes
  ASSERT_EQ(memcmp(buffer, data + 3, 2), 0);
  ASSERT_TRUE(queue.isEmpty());
}

// Test dequeue() with an empty queue
TEST_F(DataQueueTest, DequeueEmpty) {
  uint8_t buffer[10];
  uint16_t actualLen;
  ASSERT_FALSE(queue.dequeue(buffer, sizeof(buffer), actualLen));
}

// Test enqueue() with NULL data and zero data length
TEST_F(DataQueueTest, EnqueueInvalidInput) {
  ASSERT_FALSE(queue.enqueue(nullptr, 10));
  ASSERT_FALSE(queue.enqueue(reinterpret_cast<uint8_t*>(0x1234), 0));
}