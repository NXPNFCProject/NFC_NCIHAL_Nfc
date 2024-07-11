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

import android.annotation.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Copied over from frameworks/base/core/java/com/android/internal/util/ArrayUtils.java
 */
public class ArrayUtils {
    private ArrayUtils() { /* cannot be instantiated */ }

    /**
     * Return first index of {@code value} in {@code array}, or {@code -1} if
     * not found.
     */
    public static <T> int indexOf(@Nullable T[] array, T value) {
        if (array == null) return -1;
        for (int i = 0; i < array.length; i++) {
            if (Objects.equals(array[i], value)) return i;
        }
        return -1;
    }

    /**
     * Checks if given array is null or has zero elements.
     */
    public static boolean isEmpty(@Nullable int[] array) {
        return array == null || array.length == 0;
    }

    /**
     * True if the byte array is null or has length 0.
     */
    public static boolean isEmpty(@Nullable byte[] array) {
        return array == null || array.length == 0;
    }

    /**
     * Converts from List of bytes to byte array
     * @param list
     * @return byte[]
     */
    public static byte[] toPrimitive(List<byte[]> list) {
        if (list.size() == 0) {
            return new byte[0];
        }
        int byteLen = list.get(0).length;
        byte[] array = new byte[list.size() * byteLen];
        for (int i = 0; i < list.size(); i++) {
            for (int j = 0; j < list.get(i).length; j++) {
                array[i * byteLen + j] = list.get(i)[j];
            }
        }
        return array;
    }
}
