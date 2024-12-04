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
package com.android.nfc.cardemulation.util;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class NfcFileUtils {
    private static final String TAG = "NfcFileUtils";

    /**
     * Try our best to migrate all files from source to target.
     *
     * @return the number of files moved, or -1 if there was trouble.
     */
    public static int moveFiles(File sourceDir, File targetDir) {
        final File[] sourceFiles = sourceDir.listFiles();
        if (sourceFiles == null) return -1;
        int res = 0;
        for (File sourceFile : sourceFiles) {
            final File targetFile = new File(targetDir, sourceFile.getName());
            Log.d(TAG, "Migrating " + sourceFile + " to " + targetFile);
            try {
                Files.move(sourceFile.toPath(), targetFile.toPath(), REPLACE_EXISTING);
                if (res != -1) {
                    res++;
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to migrate " + sourceFile + ": " + e);
                res = -1;
            }
        }
        return res;
    }
}
