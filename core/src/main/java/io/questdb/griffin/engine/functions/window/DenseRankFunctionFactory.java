/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2023 QuestDB
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

package io.questdb.griffin.engine.functions.window;

import io.questdb.cairo.*;
import io.questdb.cairo.map.Map;
import io.questdb.cairo.map.MapFactory;
import io.questdb.cairo.map.MapKey;
import io.questdb.cairo.map.MapValue;
import io.questdb.cairo.sql.Record;
import io.questdb.cairo.sql.*;
import io.questdb.griffin.FunctionFactory;
import io.questdb.griffin.PlanSink;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.griffin.engine.RecordComparator;
import io.questdb.griffin.engine.functions.LongFunction;
import io.questdb.griffin.engine.orderby.RecordComparatorCompiler;
import io.questdb.griffin.engine.window.WindowContext;
import io.questdb.griffin.engine.window.WindowFunction;
import io.questdb.std.IntList;
import io.questdb.std.Misc;
import io.questdb.std.ObjList;
import io.questdb.std.Unsafe;

public class DenseRankFunctionFactory implements FunctionFactory {

    private static final String SIGNATURE = "dense_rank()";

    @Override
    public String getSignature() { 
        return SIGNATURE;
    }

    @Override
    public boolean isWindow() {
        return true;
    }

    @Override
    public Function newInstance(
            int position,
            ObjList<Function> args,
            IntList argPositions,
            CairoConfiguration configuration,
            SqlExecutionContext sqlExecutionContext
    ) throws SqlException {
        final WindowContext windowContext = sqlExecutionContext.getWindowContext();
        if (windowContext.isEmpty()) {
            throw SqlException.emptyWindowContext(position);
        }

        if (windowContext.getPartitionByRecord() != null) { // if window context is partitioned by 
            ArrayColumnTypes arrayColumnTypes = new ArrayColumnTypes(); // add 
            arrayColumnTypes.add(ColumnType.LONG); // rank index
            arrayColumnTypes.add(ColumnType.LONG); // current index
            arrayColumnTypes.add(ColumnType.LONG); // offset
            Map map = MapFactory.createMap(configuration, windowContext.getPartitionByKeyTypes(), arrayColumnTypes);
            return new DenseRankFunction(map, windowContext.getPartitionByRecord(), windowContext.getPartitionBySink());
        }
        if (windowContext.isOrdered()) {
            return new OrderDenseRankFunction();
        }
        return new SequenceDenseRankFunction();
    }

    private static class OrderDenseRankFunction extends LongFunction implements ScalarFunction, WindowFunction, Reopenable {

        private int columnIndex;
        private long currentIndex = 0;
        private long rankIndex = 0;
        private long offset = 0;
        private RecordComparator recordComparator;

        private long value;

        public OrderDenseRankFunction() {
        }

        @Override
        public void close() {
        }

        @Override
        public void computeNext(Record record) {
            assert recordComparator == null;
            value = ++rankIndex;
        }

        @Override
        public long getLong(Record rec) {
            assert recordComparator == null;
            return value;
        }

        @Override
        public int getPassCount() {
            return recordComparator == null ? WindowFunction.ZERO_PASS : WindowFunction.ONE_PASS;
        }

        @Override
        public void initRecordComparator(RecordComparatorCompiler recordComparatorCompiler, ArrayColumnTypes chainTypes, IntList order) {
            this.recordComparator = recordComparatorCompiler.compile(chainTypes, order);
        }

        @Override
        public void pass1(Record record, long recordOffset, WindowSPI spi) {
            if (recordComparator == null) {
                // order dismiss 
                Unsafe.getUnsafe().putLong(spi.getAddress(recordOffset, columnIndex), rankIndex + 1);
                rankIndex++;
            } else {
                if (currentIndex == 0) {
                    currentIndex = 1;
                    offset = recordOffset;
                } else {
                    // compare with prev record
                    recordComparator.setLeft(record);
                    if (recordComparator.compare(spi.getRecordAt(offset)) != 0) {
                        currentIndex = rankIndex + 1;
                        offset = recordOffset;
                        rankIndex++;
                    }
                }
                Unsafe.getUnsafe().putLong(spi.getAddress(recordOffset, columnIndex), currentIndex);
            }
        }
        // rankIndex++;

        @Override
        public void reopen() {
            reset();
        }

        @Override
        public void reset() {
            rankIndex = 0;
            currentIndex = 0;
            offset = 0;
        }

        @Override
        public void setColumnIndex(int columnIndex) {
            this.columnIndex = columnIndex;
        }

        @Override
        public void toPlan(PlanSink sink) {
            sink.val(SIGNATURE);
        }

        @Override
        public void toTop() {
            reset();
        }
    }

    private static class DenseRankFunction extends LongFunction implements ScalarFunction, WindowFunction, Reopenable {

        private final static int VAL_CURRENT_INDEX = 1;
        private final static int VAL_RANK_INDEX = 0;
        private final static int VAL_OFFSET = 2;
        private final Map map;
        private final VirtualRecord partitionByRecord;
        private final RecordSink partitionBySink;
        private int columnIndex;
        private RecordComparator recordComparator;

        private long value;

        public DenseRankFunction(Map map, VirtualRecord partitionByRecord, RecordSink partitionBySink) {
            this.partitionByRecord = partitionByRecord;
            this.partitionBySink = partitionBySink;
            this.map = map;
        }

        @Override
        public void close() {
            Misc.free(map);
            Misc.freeObjList(partitionByRecord.getFunctions());
        }

        @Override
        public void computeNext(Record record) {
            partitionByRecord.of(record);

            MapKey mapKey = map.withKey();
            mapKey.put(partitionByRecord, partitionBySink);
            MapValue mapValue = mapKey.createValue();
            long rankIndex = 0;
            if (mapValue.isNew()) {
                mapValue.putLong(VAL_RANK_INDEX, 0);
                mapValue.putLong(VAL_CURRENT_INDEX, 0);
                mapValue.putLong(VAL_OFFSET, 0);
            } else {
                rankIndex = mapValue.getLong(VAL_RANK_INDEX);
            }

            assert recordComparator == null;
            value = rankIndex + 1;
            mapValue.putLong(VAL_RANK_INDEX, value);
        }

        @Override
        public long getLong(Record rec) {
            assert recordComparator == null;
            return value;
        }

        @Override
        public int getPassCount() {
            return recordComparator == null ? WindowFunction.ZERO_PASS : WindowFunction.ONE_PASS;
        }

        @Override
        public void initRecordComparator(RecordComparatorCompiler recordComparatorCompiler, ArrayColumnTypes chainTypes, IntList order) {
            this.recordComparator = recordComparatorCompiler.compile(chainTypes, order);
        }

        @Override
        public void pass1(Record record, long recordOffset, WindowSPI spi) {
            partitionByRecord.of(record);

            MapKey mapKey = map.withKey();
            mapKey.put(partitionByRecord, partitionBySink);
            MapValue mapValue = mapKey.createValue();
            long rankIndex = 0;
            if (mapValue.isNew()) {
                mapValue.putLong(VAL_RANK_INDEX, 0);
                mapValue.putLong(VAL_CURRENT_INDEX, 0);
                mapValue.putLong(VAL_OFFSET, 0);
            } else {
                rankIndex = mapValue.getLong(VAL_RANK_INDEX);
            }

            if (recordComparator == null) {
                // no order or order dismiss
                Unsafe.getUnsafe().putLong(spi.getAddress(recordOffset, columnIndex), rankIndex + 1);
                mapValue.putLong(VAL_RANK_INDEX, rankIndex + 1);
            } else {
                long currentIndex = mapValue.getLong(VAL_CURRENT_INDEX);
                long offset = mapValue.getLong(VAL_OFFSET);
                if (currentIndex == 0) {
                    mapValue.putLong(VAL_CURRENT_INDEX, 1);
                    mapValue.putLong(VAL_OFFSET, recordOffset);
                    mapValue.putLong(VAL_RANK_INDEX, rankIndex + 1);
                } else {
                    // compare with prev record
                    recordComparator.setLeft(record);
                    if (recordComparator.compare(spi.getRecordAt(offset)) != 0) {
                        mapValue.putLong(VAL_CURRENT_INDEX, rankIndex + 1);
                        mapValue.putLong(VAL_OFFSET, recordOffset);

                    }
                }
                Unsafe.getUnsafe().putLong(spi.getAddress(recordOffset, columnIndex), mapValue.getLong(VAL_CURRENT_INDEX));
            }
            //mapValue.putLong(VAL_RANK_INDEX, rankIndex + 1);
        }

        @Override
        public void reopen() {
            map.reopen();
        }

        @Override
        public void reset() {
            map.close();
        }

        @Override
        public void setColumnIndex(int columnIndex) {
            this.columnIndex = columnIndex;
        }

        @Override
        public void toPlan(PlanSink sink) {
            sink.val(SIGNATURE);
            sink.val(" over (");
            sink.val("partition by ");
            sink.val(partitionByRecord.getFunctions());
            sink.val(')');
        }

        @Override
        public void toTop() {
            map.clear();
        }
    }

    private static class SequenceDenseRankFunction extends LongFunction implements ScalarFunction, WindowFunction, Reopenable {

        private int columnIndex;

        private long rank;

        public SequenceDenseRankFunction() {
        }

        @Override
        public void close() {
        }

        @Override
        public void computeNext(Record record) {
            this.rank = 1;
        }

        @Override
        public long getLong(Record rec) {
            return rank;
        }

        @Override
        public void initRecordComparator(RecordComparatorCompiler recordComparatorCompiler, ArrayColumnTypes chainTypes, IntList order) {
        }

        @Override
        public void pass1(Record record, long recordOffset, WindowSPI spi) {
            computeNext(record);
            Unsafe.getUnsafe().putLong(spi.getAddress(recordOffset, columnIndex), rank);
        }

        @Override
        public void reopen() {
        }

        @Override
        public void reset() {
        }

        @Override
        public void setColumnIndex(int columnIndex) {
            this.columnIndex = columnIndex;
        }

        @Override
        public void toPlan(PlanSink sink) {
            sink.val(SIGNATURE);
        }
    }
}
