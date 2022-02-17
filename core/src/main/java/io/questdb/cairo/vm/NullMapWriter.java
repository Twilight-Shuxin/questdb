/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cairo.vm;

import io.questdb.cairo.MapWriter;

public class NullMapWriter implements MapWriter {
    public static final MapWriter INSTANCE = new NullMapWriter();

    @Override
    public void appendSymbolCharsBlock(long blockSize, long sourceAddress) {
    }

    @Override
    public boolean isCached() {
        return true;
    }

    @Override
    public int put(char c) {
        return 0;
    }

    @Override
    public int put(CharSequence symbol) {
        return 0;
    }

    @Override
    public void rollback(int symbolCount) {
    }

    @Override
    public void setSymbolIndexInTxWriter(int symbolIndexInTxWriter) {
    }

    @Override
    public void truncate() {
    }

    @Override
    public void updateCacheFlag(boolean flag) {
    }

    @Override
    public void updateNullFlag(boolean flag) {
    }

    @Override
    public int getSymbolCount() {
        return 0;
    }
}