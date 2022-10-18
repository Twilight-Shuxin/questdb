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

package io.questdb.griffin.engine.union;

import io.questdb.cairo.RecordSink;
import io.questdb.cairo.map.Map;
import io.questdb.cairo.map.MapKey;
import io.questdb.cairo.sql.Record;
import io.questdb.cairo.sql.*;
import io.questdb.griffin.SqlException;
import io.questdb.std.Misc;
import io.questdb.std.ObjList;

class ExceptCastRecordCursor extends AbstractSetRecordCursor {
    private final Map map;
    private final RecordSink recordSink;
    private final UnionCastRecord castRecord;
    // this is the B record of except cursor, required by sort algo
    private UnionCastRecord recordB;
    private boolean isOpen;

    public ExceptCastRecordCursor(Map map, RecordSink recordSink, ObjList<Function> castFunctionsA, ObjList<Function> castFunctionsB) {
        this.map = map;
        this.isOpen = true;
        this.recordSink = recordSink;
        this.castRecord = new UnionCastRecord(castFunctionsA, castFunctionsB);
    }

    @Override
    public void close() {
        if (isOpen) {
            isOpen = false;
            map.close();
            super.close();
        }
    }

    void of(RecordCursor cursorA, RecordCursor cursorB, SqlExecutionCircuitBreaker circuitBreaker) throws SqlException {
        super.of(cursorA, cursorB, circuitBreaker);
        this.castRecord.of(cursorA.getRecord(), cursorB.getRecord());
        this.castRecord.setAb(false);
        if (!isOpen) {
            isOpen = true;
            map.reopen();
        }
        hashCursorB();
        castRecord.setAb(true);
        toTop();
    }

    @Override
    public Record getRecord() {
        return castRecord;
    }

    @Override
    public boolean hasNext() {
        while (cursorA.hasNext()) {
            MapKey key = map.withKey();
            key.put(castRecord, recordSink);
            if (key.notFound()) {
                return true;
            }
            circuitBreaker.statefulThrowExceptionIfTripped();
        }
        return false;
    }

    @Override
    public Record getRecordB() {
        if (recordB == null) {
            recordB = new UnionCastRecord(castRecord.getCastFunctionsA(), castRecord.getCastFunctionsB());
            recordB.setAb(true);
            // we do not need cursorB here, it is likely to be closed anyway
            recordB.of(cursorA.getRecordB(), null);
        }
        return recordB;
    }

    @Override
    public void recordAt(Record record, long atRowId) {
        cursorA.recordAt(((UnionCastRecord) record).getRecordA(), atRowId);
    }

    @Override
    public void toTop() {
        cursorA.toTop();
    }

    @Override
    public long size() {
        return -1;
    }

    private void hashCursorB() {
        while (cursorB.hasNext()) {
            MapKey key = map.withKey();
            key.put(castRecord, recordSink);
            key.createValue();
            circuitBreaker.statefulThrowExceptionIfTripped();
        }
        // this is an optimisation to release TableReader in case "this"
        // cursor lingers around. If there is exception or circuit breaker fault
        // we will rely on close() method to release reader.
        this.cursorB = Misc.free(this.cursorB);
    }
}