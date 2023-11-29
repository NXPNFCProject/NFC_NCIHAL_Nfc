/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.app.PendingIntentProto;
import android.content.AuthorityEntryProto;
import android.content.ComponentName;
import android.content.ComponentNameProto;
import android.content.IntentFilter;
import android.content.IntentFilterProto;
import android.os.PatternMatcher;
import android.os.PatternMatcherProto;
import android.util.proto.ProtoOutputStream;

import java.util.Iterator;
import java.util.Objects;

public final class Utils {
    private Utils() {
    }

    /**
     * Returns true if the given {@code array} contains the given element.
     *
     * @param array {@code array} to check for {@code elem}
     * @param elem {@code elem} to test for
     * @return {@code true} if the given element is contained
     */
    public static <T> boolean arrayContains(@Nullable T[] array, T elem) {
        if (array == null) {
            return false;
        }
        for (int i = 0; i < array.length; i++) {
            if (Objects.equals(array[i], elem)) {
                return true;
            }
        }
        return false;
    }

    /** Ported (this uses public API's as opposed to private fields used in the original impl from
     * {@link IntentFilter#dumpDebug(ProtoOutputStream, long)} */
    public static void dumpDebugIntentFilter(
            @NonNull IntentFilter intentFilter, @NonNull ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        for (int i = 0; i < intentFilter.countActions(); i++) {
            proto.write(IntentFilterProto.ACTIONS, intentFilter.getAction(i));
        }
        for (int i = 0; i < intentFilter.countCategories(); i++) {
            proto.write(IntentFilterProto.CATEGORIES, intentFilter.getCategory(i));
        }
        for (int i = 0; i < intentFilter.countDataSchemes(); i++) {
            proto.write(IntentFilterProto.DATA_SCHEMES, intentFilter.getDataScheme(i));
        }
        for (int i = 0; i < intentFilter.countDataSchemeSpecificParts(); i++) {
            dumpDebugPatternMatcher(
                    intentFilter.getDataSchemeSpecificPart(i), proto,
                    IntentFilterProto.DATA_SCHEME_SPECS);
        }
        for (int i = 0; i < intentFilter.countDataAuthorities(); i++) {
            dumpDebugAuthorityEntry(
                    intentFilter.getDataAuthority(i), proto,
                    IntentFilterProto.DATA_AUTHORITIES);
        }
        for (int i = 0; i < intentFilter.countDataPaths(); i++) {
            dumpDebugPatternMatcher(
                    intentFilter.getDataPath(i), proto,
                    IntentFilterProto.DATA_PATHS);
        }
        for (int i = 0; i < intentFilter.countDataTypes(); i++) {
            proto.write(IntentFilterProto.DATA_TYPES, intentFilter.getDataType(i));
        }
        /*
        for (int i = 0; i < intentFilter.countMimeGroups(); i++) {
            proto.write(IntentFilterProto.MIME_GROUPS, intentFilter.getMimeGroup(i));
        }*/

        if (intentFilter.getPriority() != 0
                /* || TODO(b/271463752): Get this info - hasPartialTypes() */) {
            proto.write(IntentFilterProto.PRIORITY, intentFilter.getPriority());
            proto.write(IntentFilterProto.HAS_PARTIAL_TYPES, false /* hasPartialTypes() */);
        }
        proto.write(IntentFilterProto.GET_AUTO_VERIFY, false /* intentFilter.getAutoVerify() */);
        proto.end(token);
    }

    /** Ported (this uses public API's as opposed to private fields used in the original impl from
     * {@link PatternMatcher#dumpDebug(ProtoOutputStream, long)} */
    private static void dumpDebugPatternMatcher(@NonNull PatternMatcher patternMatcher,
            @NonNull ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(PatternMatcherProto.PATTERN, patternMatcher.getPath());
        proto.write(PatternMatcherProto.TYPE, patternMatcher.getType());
        // PatternMatcherProto.PARSED_PATTERN is too much to dump, but the field is reserved to
        // match the current data structure.
        proto.end(token);
    }

    /** Ported (this uses public API's as opposed to private fields used in the original impl from
     * {@link IntentFilter.AuthorityEntry#dumpDebug(ProtoOutputStream, long)} */
    private static void dumpDebugAuthorityEntry(
            @NonNull IntentFilter.AuthorityEntry authorityEntry, ProtoOutputStream proto,
            long fieldId) {
        long token = proto.start(fieldId);
        // The public API's only give the orig host name. The fields in the proto are derived from
        // this host info. {@see AuthorityEntry{String, String}
        String origHost = authorityEntry.getHost();
        boolean wild = origHost.length() > 0 && origHost.charAt(0) == '*';
        String host = wild ? origHost.substring(1).intern() : origHost;
        proto.write(AuthorityEntryProto.HOST, host);
        proto.write(AuthorityEntryProto.WILD, wild);
        proto.write(AuthorityEntryProto.PORT, authorityEntry.getPort());
        proto.end(token);
    }

    /** Ported (this uses public API's as opposed to private fields used in the original impl from
     * {@link PendingIntent#dumpDebug(ProtoOutputStream, long)} */
    public static void dumpDebugPendingIntent(
            @NonNull PendingIntent pendingIntent, @NonNull ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        // TODO: This is not exactly the same format as the original impl. But, it still prints
        // the target info.
        proto.write(PendingIntentProto.TARGET, pendingIntent.toString());
        proto.end(token);
    }
}
