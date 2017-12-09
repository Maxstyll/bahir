/*
 * Copyright (c) 2017 IBM Cloudant. All rights reserved.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package org.apache.bahir.cloudant.common;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.BufferedReader;
import java.io.IOException;

/**
 * This scanner will read through a _changes stream until it finds the
 * next meaningful row, either a change entry or the closing line with
 * the lastSeq and, perhaps, pending changes (for normal/longpoll feeds).
 */
public class ChangesRowScanner {

    private static final Gson gson = new Gson();

    /**
     * Read up to the next meaningful line from the changes feed, and calls
     * the passed delegate depending on what it finds. Works for all styles of
     * changes feed (normal, longpoll, continuous).
     *
     * @return True if should continue
     *
     * @throws IOException if there's a problem reading the stream
     */
    public static ChangesRow readRowFromReader(BufferedReader changesReader)
            throws IOException {

        String line;

        // Read the next line (empty = heartbeat, ignore; null = end of stream)
        while (true) {
            line = changesReader.readLine();
            if (line != null && line.isEmpty()) {
                continue;
            }
            if (line.startsWith("{\"results\":")) {
                // ignore, just the START of the result set in normal/longpoll mode
                continue;
            } else if (line.startsWith("],")) {
                // ignore, just the END of the result set in normal/longpoll mode
                continue;
            }
            break;
        }

        if (line.startsWith("\"last_seq\":")) {
            return null; // End of feed
        } else if (line.startsWith("{\"last_seq\":")) {
            return null; // End of feed
        } else {
            if (line.endsWith(",")) {
                line = line.substring(0, line.length() - 1);
            }
            ChangesRow r = gson.fromJson(line, ChangesRow.class);
            return r; // not end of feed
        }
    }



    /**
     * Extract a seq value from a JSON line of the form
     * {..., "last_seq": "...", ...}
     */
    private static String extractSeq(String line) {
        JsonObject lastSeq = gson.fromJson(line, JsonObject.class);
        JsonPrimitive primitive = lastSeq.getAsJsonPrimitive("last_seq");
        String lastSeqValue = null;
        if (primitive.isString()) {
            lastSeqValue = primitive.getAsString();
        } else if (primitive.isNumber()) {
            lastSeqValue = String.valueOf(primitive.getAsLong());
        }
        return lastSeqValue;
    }

}
