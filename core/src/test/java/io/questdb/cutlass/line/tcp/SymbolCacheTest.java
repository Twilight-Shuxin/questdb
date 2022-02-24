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

package io.questdb.cutlass.line.tcp;

import io.questdb.cairo.*;
import io.questdb.cairo.sql.SymbolTable;
import io.questdb.cairo.vm.Vm;
import io.questdb.cairo.vm.api.MemoryMR;
import io.questdb.griffin.AbstractGriffinTest;
import io.questdb.mp.RingQueue;
import io.questdb.mp.SCSequence;
import io.questdb.mp.SOCountDownLatch;
import io.questdb.mp.SPSequence;
import io.questdb.std.*;
import io.questdb.std.datetime.microtime.TimestampFormatUtils;
import io.questdb.std.str.Path;
import io.questdb.test.tools.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.questdb.cairo.TableUtils.COLUMN_VERSION_FILE_NAME;

public class SymbolCacheTest extends AbstractGriffinTest {

    @Test
    public void testConcurrency() throws Exception {
        assertMemoryLeak(() -> {
            final Rnd rndCache = new Rnd();
            final int N = 1_000_000;
            long ts = TimestampFormatUtils.parseTimestamp("2020-09-10T20:00:00.000000Z");
            final long incrementUs = 10000;
            final String constValue = "hello";
            FilesFacade ff = new FilesFacadeImpl();

            compiler.compile("create table x(a symbol, c int, b symbol capacity 10000000, ts timestamp) timestamp(ts) partition by DAY", sqlExecutionContext);

            try (
                    SymbolCache symbolCache = new SymbolCache(new DefaultLineTcpReceiverConfiguration());
                    Path path = new Path();
                    TxReader txReader = new TxReader(ff).ofRO(path.of(configuration.getRoot()).concat("x"), PartitionBy.DAY);
                    ColumnVersionReader columnVersionReader = new ColumnVersionReader().ofRO(ff, path.of(configuration.getRoot()).concat("x").concat(COLUMN_VERSION_FILE_NAME).$())
            ) {
                path.of(configuration.getRoot()).concat("x");
                symbolCache.of(configuration, path, "b", 1, txReader, columnVersionReader, 1);

                final CyclicBarrier barrier = new CyclicBarrier(2);
                final SOCountDownLatch haltLatch = new SOCountDownLatch(1);
                final AtomicBoolean cacheInError = new AtomicBoolean(false);

                RingQueue<Holder> wheel = new RingQueue<>(Holder::new, 256);
                SPSequence pubSeq = new SPSequence(wheel.getCycle());
                SCSequence subSeq = new SCSequence();
                pubSeq.then(subSeq).then(pubSeq);

                new Thread(() -> {
                    try {
                        barrier.await();
                        for (int i = 0; i < N; i++) {
                            // All keys should not be found, but we keep looking them up because
                            // we pretend we don't know this upfront. The aim is to cause
                            // race condition between lookup and table writer
                            final CharSequence value2 = rndCache.nextString(5);
                            symbolCache.keyOf(constValue);
                            symbolCache.keyOf(value2);
                            final long cursor = pubSeq.nextBully();
                            final Holder h = wheel.get(cursor);
                            // publish the value2 to the table writer
                            h.value1 = constValue;
                            h.value2 = Chars.toString(value2);
                            pubSeq.done(cursor);
                        }
                    } catch (Throwable e) {
                        cacheInError.set(true);
                        e.printStackTrace();
                    } finally {
                        haltLatch.countDown();
                    }
                }).start();


                try (TableWriter w = engine.getWriter(sqlExecutionContext.getCairoSecurityContext(), "x", "test")) {
                    barrier.await();

                    OUT:
                    for (int i = 0; i < N; i++) {
                        long cursor;
                        while (true) {
                            cursor = subSeq.next();
                            if (cursor < 0) {
                                // we should exist main loop even if we did not receive N values
                                // the publishing thread could get successful cache lookups and not publish value
                                // due to random generator producing duplicate strings
                                if (haltLatch.getCount() < 1) {
                                    break OUT;
                                }
                            } else {
                                break;
                            }
                        }
                        Holder h = wheel.get(cursor);
                        TableWriter.Row r = w.newRow(ts);
                        r.putSym(0, h.value1);
                        r.putInt(1, 0);
                        r.putSym(2, h.value2);
                        r.append();
                        subSeq.done(cursor);

                        if (i % 256 == 0) {
                            w.commit();
                        }
                        ts += incrementUs;
                    }
                    w.commit();
                } finally {
                    haltLatch.await();
                }
                Assert.assertFalse(cacheInError.get());
            }
            compiler.compile("drop table x", sqlExecutionContext);
        });
    }

    @Test
    public void testSimpleInteraction() throws Exception {
        String tableName = "tb1";
        FilesFacade ff = new FilesFacadeImpl();
        TestUtils.assertMemoryLeak(() -> {
            try (Path path = new Path();
                 TableModel model = new TableModel(configuration, tableName, PartitionBy.DAY)
                         .col("symCol1", ColumnType.SYMBOL)
                         .col("symCol2", ColumnType.SYMBOL);
                 SymbolCache cache = new SymbolCache(new DefaultLineTcpReceiverConfiguration() {
                     @Override
                     public long getSymbolCacheWaitUsBeforeReload() {
                         return 0;
                     }
                 })
            ) {
                CairoTestUtils.create(model);
                try (
                        TableWriter writer = new TableWriter(configuration, tableName, metrics);
                        MemoryMR txMem = Vm.getMRInstance();
                        TxReader txReader = new TxReader(ff).ofRO(path.of(configuration.getRoot()).concat(tableName), PartitionBy.DAY);
                        ColumnVersionReader columnVersionReader = new ColumnVersionReader().ofRO(ff, path.of(configuration.getRoot()).concat(tableName).concat(COLUMN_VERSION_FILE_NAME).$())
                ) {
                    int symColIndex1 = writer.getColumnIndex("symCol1");
                    int symColIndex2 = writer.getColumnIndex("symCol2");
                    long transientSymCountOffset = TableUtils.getSymbolWriterTransientIndexOffset(symColIndex2);
                    path.of(configuration.getRoot()).concat(tableName);

                    txMem.of(
                            configuration.getFilesFacade(),
                            path.concat(TableUtils.TXN_FILE_NAME).$(),
                            transientSymCountOffset + Integer.BYTES,
                            transientSymCountOffset + Integer.BYTES,
                            MemoryTag.MMAP_DEFAULT
                    );

                    cache.of(
                            configuration,
                            path.of(configuration.getRoot()).concat(tableName),
                            "symCol2",
                            symColIndex2,
                            txReader,
                            columnVersionReader,
                            symColIndex2
                    );

                    TableWriter.Row r = writer.newRow();
                    r.putSym(symColIndex1, "sym11");
                    r.putSym(symColIndex2, "sym21");
                    r.append();
                    writer.commit();
                    Assert.assertEquals(0, txReader.unsafeReadSymbolCount(1));
                    Assert.assertEquals(1, txReader.unsafeReadSymbolTransientCount(1));
                    txReader.unsafeLoadAll();
                    Assert.assertEquals(1, txReader.unsafeReadSymbolCount(1));
                    Assert.assertEquals(1, txReader.unsafeReadSymbolTransientCount(1));

                    int rc = cache.getSymbolKey("missing");
                    Assert.assertEquals(SymbolTable.VALUE_NOT_FOUND, rc);
                    Assert.assertEquals(0, cache.getCacheValueCount());
                    rc = cache.getSymbolKey("sym21");
                    Assert.assertEquals(0, rc);
                    Assert.assertEquals(1, cache.getCacheValueCount());

                    r = writer.newRow();
                    r.putSym(symColIndex1, "sym12");
                    r.putSym(symColIndex2, "sym21");
                    r.append();
                    writer.commit();
                    Assert.assertEquals(1, txReader.unsafeReadSymbolCount(1));
                    Assert.assertEquals(1, txReader.unsafeReadSymbolTransientCount(1));
                    rc = cache.getSymbolKey("missing");
                    Assert.assertEquals(SymbolTable.VALUE_NOT_FOUND, rc);
                    Assert.assertEquals(1, cache.getCacheValueCount());
                    rc = cache.getSymbolKey("sym21");
                    Assert.assertEquals(0, rc);
                    Assert.assertEquals(1, cache.getCacheValueCount());

                    r = writer.newRow();
                    r.putSym(symColIndex1, "sym12");
                    r.putSym(symColIndex2, "sym22");
                    r.append();
                    Assert.assertEquals(1, txReader.unsafeReadSymbolCount(1));
                    Assert.assertEquals(2, txReader.unsafeReadSymbolTransientCount(1));
                    writer.commit();
                    Assert.assertEquals(1, txReader.unsafeReadSymbolCount(1));
                    Assert.assertEquals(2, txReader.unsafeReadSymbolTransientCount(1));
                    rc = cache.getSymbolKey("sym21");
                    Assert.assertEquals(0, rc);
                    Assert.assertEquals(1, cache.getCacheValueCount());
                    rc = cache.getSymbolKey("sym22");
                    Assert.assertEquals(1, rc);
                    Assert.assertEquals(2, cache.getCacheValueCount());

                    txReader.unsafeLoadAll();
                    // Test cached uncommitted symbols
                    r = writer.newRow();
                    r.putSym(symColIndex1, "sym12");
                    r.putSym(symColIndex2, "sym23");
                    r.append();
                    r = writer.newRow();
                    r.putSym(symColIndex1, "sym12");
                    r.putSym(symColIndex2, "sym24");
                    r.append();
                    r = writer.newRow();
                    r.putSym(symColIndex1, "sym12");
                    r.putSym(symColIndex2, "sym25");
                    r.append();

                    rc = cache.getSymbolKey("sym22");
                    Assert.assertEquals(1, rc);
                    Assert.assertEquals(2, cache.getCacheValueCount());
                    rc = cache.getSymbolKey("sym24");
                    Assert.assertEquals(3, rc);
                    Assert.assertEquals(3, cache.getCacheValueCount());
                    writer.commit();

                    // Test deleting a symbol column
                    writer.removeColumn("symCol1");
                    cache.close();
                    txMem.close();
                    path.of(configuration.getRoot()).concat(tableName);

                    cache.of(
                            configuration,
                            path.of(configuration.getRoot()).concat(tableName),
                            "symCol2",
                            0,
                            txReader,
                            columnVersionReader,
                            1
                    );

                    rc = cache.getSymbolKey("sym24");
                    Assert.assertEquals(3, rc);
                    Assert.assertEquals(1, cache.getCacheValueCount());

                    r = writer.newRow();
                    r.putSym(symColIndex2, "sym26");
                    r.append();
                    rc = cache.getSymbolKey("sym26");
                    Assert.assertEquals(5, rc);
                    Assert.assertEquals(2, cache.getCacheValueCount());
                    writer.commit();
                    rc = cache.getSymbolKey("sym26");
                    Assert.assertEquals(5, rc);
                    Assert.assertEquals(2, cache.getCacheValueCount());
                }
            }
        });
    }

    private static class Holder implements Mutable {
        String value1;
        String value2;

        @Override
        public void clear() {
            value1 = null;
            value2 = null;
        }
    }
}
