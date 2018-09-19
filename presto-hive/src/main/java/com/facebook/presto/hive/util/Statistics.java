/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.hive.util;

import com.facebook.presto.hive.HiveBasicStatistics;
import com.facebook.presto.hive.PartitionStatistics;
import com.facebook.presto.hive.metastore.BooleanStatistics;
import com.facebook.presto.hive.metastore.DateStatistics;
import com.facebook.presto.hive.metastore.DecimalStatistics;
import com.facebook.presto.hive.metastore.DoubleStatistics;
import com.facebook.presto.hive.metastore.HiveColumnStatistics;
import com.facebook.presto.hive.metastore.IntegerStatistics;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.statistics.ColumnStatisticMetadata;
import com.facebook.presto.spi.statistics.ColumnStatisticType;
import com.facebook.presto.spi.statistics.ComputedStatistics;
import com.facebook.presto.spi.type.DecimalType;
import com.facebook.presto.spi.type.Decimals;
import com.facebook.presto.spi.type.SqlDate;
import com.facebook.presto.spi.type.SqlDecimal;
import com.facebook.presto.spi.type.Type;
import com.google.common.collect.ImmutableMap;
import org.joda.time.DateTimeZone;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;

import static com.facebook.presto.hive.HiveWriteUtils.createPartitionValues;
import static com.facebook.presto.hive.util.Statistics.ReduceOperator.ADD;
import static com.facebook.presto.hive.util.Statistics.ReduceOperator.MAX;
import static com.facebook.presto.hive.util.Statistics.ReduceOperator.MIN;
import static com.facebook.presto.spi.statistics.ColumnStatisticType.MAX_VALUE;
import static com.facebook.presto.spi.statistics.ColumnStatisticType.MAX_VALUE_SIZE_IN_BYTES;
import static com.facebook.presto.spi.statistics.ColumnStatisticType.MIN_VALUE;
import static com.facebook.presto.spi.statistics.ColumnStatisticType.NUMBER_OF_DISTINCT_VALUES;
import static com.facebook.presto.spi.statistics.ColumnStatisticType.NUMBER_OF_NON_NULL_VALUES;
import static com.facebook.presto.spi.statistics.ColumnStatisticType.NUMBER_OF_TRUE_VALUES;
import static com.facebook.presto.spi.statistics.ColumnStatisticType.TOTAL_SIZE_IN_BYTES;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.DateType.DATE;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.IntegerType.INTEGER;
import static com.facebook.presto.spi.type.RealType.REAL;
import static com.facebook.presto.spi.type.SmallintType.SMALLINT;
import static com.facebook.presto.spi.type.TimestampType.TIMESTAMP;
import static com.facebook.presto.spi.type.TinyintType.TINYINT;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.Sets.intersection;
import static java.lang.Float.floatToRawIntBits;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public final class Statistics
{
    private Statistics() {}

    public static PartitionStatistics merge(PartitionStatistics first, PartitionStatistics second)
    {
        return new PartitionStatistics(
                reduce(first.getBasicStatistics(), second.getBasicStatistics(), ADD),
                merge(first.getColumnStatistics(), second.getColumnStatistics()));
    }

    public static HiveBasicStatistics reduce(HiveBasicStatistics first, HiveBasicStatistics second, ReduceOperator operator)
    {
        return new HiveBasicStatistics(
                reduce(first.getFileCount(), second.getFileCount(), operator, false),
                reduce(first.getRowCount(), second.getRowCount(), operator, false),
                reduce(first.getInMemoryDataSizeInBytes(), second.getInMemoryDataSizeInBytes(), operator, false),
                reduce(first.getOnDiskDataSizeInBytes(), second.getOnDiskDataSizeInBytes(), operator, false));
    }

    public static Map<String, HiveColumnStatistics> merge(Map<String, HiveColumnStatistics> first, Map<String, HiveColumnStatistics> second)
    {
        // only keep columns that have statistics for both sides
        Set<String> columns = intersection(first.keySet(), second.keySet());
        return columns.stream()
                .collect(toImmutableMap(
                        column -> column,
                        column -> merge(first.get(column), second.get(column))));
    }

    public static HiveColumnStatistics merge(HiveColumnStatistics first, HiveColumnStatistics second)
    {
        return new HiveColumnStatistics(
                mergeIntegerStatistics(first.getIntegerStatistics(), second.getIntegerStatistics()),
                mergeDoubleStatistics(first.getDoubleStatistics(), second.getDoubleStatistics()),
                mergeDecimalStatistics(first.getDecimalStatistics(), second.getDecimalStatistics()),
                mergeDateStatistics(first.getDateStatistics(), second.getDateStatistics()),
                mergeBooleanStatistics(first.getBooleanStatistics(), second.getBooleanStatistics()),
                reduce(first.getMaxValueSizeInBytes(), second.getMaxValueSizeInBytes(), MAX, true),
                reduce(first.getTotalSizeInBytes(), second.getTotalSizeInBytes(), ADD, true),
                reduce(first.getNullsCount(), second.getNullsCount(), ADD, false),
                reduce(first.getDistinctValuesCount(), second.getDistinctValuesCount(), MAX, false));
    }

    private static Optional<IntegerStatistics> mergeIntegerStatistics(Optional<IntegerStatistics> first, Optional<IntegerStatistics> second)
    {
        // normally, either both or none is present
        if (first.isPresent() && second.isPresent()) {
            return Optional.of(new IntegerStatistics(
                    reduce(first.get().getMin(), second.get().getMin(), MIN, true),
                    reduce(first.get().getMax(), second.get().getMax(), MAX, true)));
        }
        return Optional.empty();
    }

    private static Optional<DoubleStatistics> mergeDoubleStatistics(Optional<DoubleStatistics> first, Optional<DoubleStatistics> second)
    {
        // normally, either both or none is present
        if (first.isPresent() && second.isPresent()) {
            return Optional.of(new DoubleStatistics(
                    reduce(first.get().getMin(), second.get().getMin(), MIN, true),
                    reduce(first.get().getMax(), second.get().getMax(), MAX, true)));
        }
        return Optional.empty();
    }

    private static Optional<DecimalStatistics> mergeDecimalStatistics(Optional<DecimalStatistics> first, Optional<DecimalStatistics> second)
    {
        // normally, either both or none is present
        if (first.isPresent() && second.isPresent()) {
            return Optional.of(new DecimalStatistics(
                    reduce(first.get().getMin(), second.get().getMin(), MIN, true),
                    reduce(first.get().getMax(), second.get().getMax(), MAX, true)));
        }
        return Optional.empty();
    }

    private static Optional<DateStatistics> mergeDateStatistics(Optional<DateStatistics> first, Optional<DateStatistics> second)
    {
        // normally, either both or none is present
        if (first.isPresent() && second.isPresent()) {
            return Optional.of(new DateStatistics(
                    reduce(first.get().getMin(), second.get().getMin(), MIN, true),
                    reduce(first.get().getMax(), second.get().getMax(), MAX, true)));
        }
        return Optional.empty();
    }

    private static Optional<BooleanStatistics> mergeBooleanStatistics(Optional<BooleanStatistics> first, Optional<BooleanStatistics> second)
    {
        // normally, either both or none is present
        if (first.isPresent() && second.isPresent()) {
            return Optional.of(new BooleanStatistics(
                    reduce(first.get().getTrueCount(), second.get().getTrueCount(), ADD, false),
                    reduce(first.get().getFalseCount(), second.get().getFalseCount(), ADD, false)));
        }
        return Optional.empty();
    }

    private static OptionalDouble mergeAverage(OptionalDouble first, OptionalLong firstRowCount, OptionalDouble second, OptionalLong secondRowCount)
    {
        if (first.isPresent() && second.isPresent()) {
            if (!firstRowCount.isPresent() || !secondRowCount.isPresent()) {
                return OptionalDouble.empty();
            }
            long totalRowCount = firstRowCount.getAsLong() + secondRowCount.getAsLong();
            if (totalRowCount == 0) {
                return OptionalDouble.empty();
            }
            double sumFirst = first.getAsDouble() * firstRowCount.getAsLong();
            double sumSecond = second.getAsDouble() * secondRowCount.getAsLong();
            return OptionalDouble.of((sumFirst + sumSecond) / totalRowCount);
        }
        return first.isPresent() ? first : second;
    }

    private static OptionalLong reduce(OptionalLong first, OptionalLong second, ReduceOperator operator, boolean returnFirstNonEmpty)
    {
        if (first.isPresent() && second.isPresent()) {
            switch (operator) {
                case ADD:
                    return OptionalLong.of(first.getAsLong() + second.getAsLong());
                case SUBTRACT:
                    return OptionalLong.of(first.getAsLong() - second.getAsLong());
                case MAX:
                    return OptionalLong.of(max(first.getAsLong(), second.getAsLong()));
                case MIN:
                    return OptionalLong.of(min(first.getAsLong(), second.getAsLong()));
                default:
                    throw new IllegalArgumentException("Unexpected operator: " + operator);
            }
        }
        if (returnFirstNonEmpty) {
            return first.isPresent() ? first : second;
        }
        return OptionalLong.empty();
    }

    private static OptionalDouble reduce(OptionalDouble first, OptionalDouble second, ReduceOperator operator, boolean returnFirstNonEmpty)
    {
        if (first.isPresent() && second.isPresent()) {
            switch (operator) {
                case ADD:
                    return OptionalDouble.of(first.getAsDouble() + second.getAsDouble());
                case SUBTRACT:
                    return OptionalDouble.of(first.getAsDouble() - second.getAsDouble());
                case MAX:
                    return OptionalDouble.of(max(first.getAsDouble(), second.getAsDouble()));
                case MIN:
                    return OptionalDouble.of(min(first.getAsDouble(), second.getAsDouble()));
                default:
                    throw new IllegalArgumentException("Unexpected operator: " + operator);
            }
        }
        if (returnFirstNonEmpty) {
            return first.isPresent() ? first : second;
        }
        return OptionalDouble.empty();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<? super T>> Optional<T> reduce(Optional<T> first, Optional<T> second, ReduceOperator operator, boolean returnFirstNonEmpty)
    {
        if (first.isPresent() && second.isPresent()) {
            switch (operator) {
                case MAX:
                    return Optional.of(max(first.get(), second.get()));
                case MIN:
                    return Optional.of(min(first.get(), second.get()));
                default:
                    throw new IllegalArgumentException("Unexpected operator: " + operator);
            }
        }
        if (returnFirstNonEmpty) {
            return first.isPresent() ? first : second;
        }
        return Optional.empty();
    }

    private static <T extends Comparable<? super T>> T max(T first, T second)
    {
        return first.compareTo(second) >= 0 ? first : second;
    }

    private static <T extends Comparable<? super T>> T min(T first, T second)
    {
        return first.compareTo(second) <= 0 ? first : second;
    }

    public static Range getMinMaxAsPrestoNativeValues(HiveColumnStatistics statistics, Type type, DateTimeZone timeZone)
    {
        if (type.equals(BIGINT) || type.equals(INTEGER) || type.equals(SMALLINT) || type.equals(TINYINT)) {
            return statistics.getIntegerStatistics().map(integerStatistics -> Range.create(
                    integerStatistics.getMin(),
                    integerStatistics.getMax()))
                    .orElse(Range.empty());
        }
        if (type.equals(DOUBLE)) {
            return statistics.getDoubleStatistics().map(doubleStatistics -> Range.create(
                    doubleStatistics.getMin(),
                    doubleStatistics.getMax()))
                    .orElse(Range.empty());
        }
        if (type.equals(REAL)) {
            return statistics.getDoubleStatistics().map(doubleStatistics -> Range.create(
                    boxed(doubleStatistics.getMin()).map(Statistics::floatAsDoubleToLongBits),
                    boxed(doubleStatistics.getMax()).map(Statistics::floatAsDoubleToLongBits)))
                    .orElse(Range.empty());
        }
        if (type.equals(DATE)) {
            return statistics.getDateStatistics().map(dateStatistics -> Range.create(
                    dateStatistics.getMin().map(LocalDate::toEpochDay),
                    dateStatistics.getMax().map(LocalDate::toEpochDay)))
                    .orElse(Range.empty());
        }
        if (type.equals(TIMESTAMP)) {
            return statistics.getIntegerStatistics().map(integerStatistics -> Range.create(
                    boxed(integerStatistics.getMin()).map(value -> convertLocalToUtc(timeZone, value)),
                    boxed(integerStatistics.getMax()).map(value -> convertLocalToUtc(timeZone, value))))
                    .orElse(Range.empty());
        }
        if (type instanceof DecimalType) {
            return statistics.getDecimalStatistics().map(decimalStatistics -> Range.create(
                    decimalStatistics.getMin().map(value -> encodeDecimal(type, value)),
                    decimalStatistics.getMax().map(value -> encodeDecimal(type, value))))
                    .orElse(Range.empty());
        }
        return Range.empty();
    }

    private static long floatAsDoubleToLongBits(double value)
    {
        return floatToRawIntBits((float) value);
    }

    private static long convertLocalToUtc(DateTimeZone timeZone, long value)
    {
        return timeZone.convertLocalToUTC(value * 1000, false);
    }

    private static Comparable<?> encodeDecimal(Type type, BigDecimal value)
    {
        BigInteger unscaled = Decimals.rescale(value, (DecimalType) type).unscaledValue();
        if (Decimals.isShortDecimal(type)) {
            return unscaled.longValueExact();
        }
        return Decimals.encodeUnscaledValue(unscaled);
    }

    public static Map<List<String>, ComputedStatistics> createComputedStatisticsToPartitionMap(
            Collection<ComputedStatistics> computedStatistics,
            List<String> partitionColumns,
            Map<String, Type> columnTypes)
    {
        List<Type> partitionColumnTypes = partitionColumns.stream()
                .map(columnTypes::get)
                .collect(toImmutableList());

        return computedStatistics.stream()
                .collect(toImmutableMap(statistics -> getPartitionValues(statistics, partitionColumns, partitionColumnTypes), statistics -> statistics));
    }

    private static List<String> getPartitionValues(ComputedStatistics statistics, List<String> partitionColumns, List<Type> partitionColumnTypes)
    {
        checkArgument(statistics.getGroupingColumns().equals(partitionColumns),
                "Unexpected grouping. Partition columns: %s. Grouping columns: %s", partitionColumns, statistics.getGroupingColumns());
        Page partitionColumnsPage = new Page(1, statistics.getGroupingValues().toArray(new Block[] {}));
        return createPartitionValues(partitionColumnTypes, partitionColumnsPage, 0);
    }

    public static Map<String, HiveColumnStatistics> fromComputedStatistics(
            ConnectorSession session,
            DateTimeZone timeZone,
            Map<ColumnStatisticMetadata, Block> computedStatistics,
            Map<String, Type> columnTypes,
            long rowCount)
    {
        return createColumnToComputedStatisticsMap(computedStatistics).entrySet().stream()
                .collect(toImmutableMap(Entry::getKey, entry -> createHiveColumnStatistics(session, timeZone, entry.getValue(), columnTypes.get(entry.getKey()), rowCount)));
    }

    private static Map<String, Map<ColumnStatisticType, Block>> createColumnToComputedStatisticsMap(Map<ColumnStatisticMetadata, Block> computedStatistics)
    {
        Map<String, Map<ColumnStatisticType, Block>> result = new HashMap<>();
        computedStatistics.forEach((metadata, block) -> {
            Map<ColumnStatisticType, Block> columnStatistics = result.computeIfAbsent(metadata.getColumnName(), key -> new HashMap<>());
            columnStatistics.put(metadata.getStatisticType(), block);
        });
        return result.entrySet()
                .stream()
                .collect(toImmutableMap(Entry::getKey, entry -> ImmutableMap.copyOf(entry.getValue())));
    }

    private static HiveColumnStatistics createHiveColumnStatistics(
            ConnectorSession session,
            DateTimeZone timeZone,
            Map<ColumnStatisticType, Block> computedStatistics,
            Type columnType,
            long rowCount)
    {
        HiveColumnStatistics.Builder result = HiveColumnStatistics.builder();

        // MIN_VALUE, MAX_VALUE
        // We ask the engine to compute either both or neither
        verify(computedStatistics.containsKey(MIN_VALUE) == computedStatistics.containsKey(MAX_VALUE));
        if (computedStatistics.containsKey(MIN_VALUE)) {
            setMinMax(session, timeZone, columnType, computedStatistics.get(MIN_VALUE), computedStatistics.get(MAX_VALUE), result);
        }

        // MAX_VALUE_SIZE_IN_BYTES
        if (computedStatistics.containsKey(MAX_VALUE_SIZE_IN_BYTES)) {
            result.setMaxValueSizeInBytes(getIntegerValue(session, BIGINT, computedStatistics.get(MAX_VALUE_SIZE_IN_BYTES)));
        }

        // TOTAL_VALUES_SIZE_IN_BYTES
        if (computedStatistics.containsKey(TOTAL_SIZE_IN_BYTES)) {
            result.setTotalSizeInBytes(getIntegerValue(session, BIGINT, computedStatistics.get(TOTAL_SIZE_IN_BYTES)));
        }

        // NDV
        if (computedStatistics.containsKey(NUMBER_OF_DISTINCT_VALUES)) {
            result.setDistinctValuesCount(BIGINT.getLong(computedStatistics.get(NUMBER_OF_DISTINCT_VALUES), 0));
        }

        // NUMBER OF NULLS
        if (computedStatistics.containsKey(NUMBER_OF_NON_NULL_VALUES)) {
            result.setNullsCount(rowCount - BIGINT.getLong(computedStatistics.get(NUMBER_OF_NON_NULL_VALUES), 0));
        }

        // NUMBER OF FALSE, NUMBER OF TRUE
        if (computedStatistics.containsKey(NUMBER_OF_TRUE_VALUES) && computedStatistics.containsKey(NUMBER_OF_NON_NULL_VALUES)) {
            long numberOfTrue = BIGINT.getLong(computedStatistics.get(NUMBER_OF_TRUE_VALUES), 0);
            long numberOfNonNullValues = BIGINT.getLong(computedStatistics.get(NUMBER_OF_NON_NULL_VALUES), 0);
            result.setBooleanStatistics(new BooleanStatistics(OptionalLong.of(numberOfTrue), OptionalLong.of(numberOfNonNullValues - numberOfTrue)));
        }
        return result.build();
    }

    private static void setMinMax(ConnectorSession session, DateTimeZone timeZone, Type type, Block min, Block max, HiveColumnStatistics.Builder result)
    {
        if (type.equals(BIGINT) || type.equals(INTEGER) || type.equals(SMALLINT) || type.equals(TINYINT)) {
            result.setIntegerStatistics(new IntegerStatistics(getIntegerValue(session, type, min), getIntegerValue(session, type, max)));
        }
        else if (type.equals(DOUBLE) || type.equals(REAL)) {
            result.setDoubleStatistics(new DoubleStatistics(getDoubleValue(session, type, min), getDoubleValue(session, type, max)));
        }
        else if (type.equals(DATE)) {
            result.setDateStatistics(new DateStatistics(getDateValue(session, type, min), getDateValue(session, type, max)));
        }
        else if (type.equals(TIMESTAMP)) {
            result.setIntegerStatistics(new IntegerStatistics(getTimestampValue(timeZone, min), getTimestampValue(timeZone, max)));
        }
        else if (type instanceof DecimalType) {
            result.setDecimalStatistics(new DecimalStatistics(getDecimalValue(session, type, min), getDecimalValue(session, type, max)));
        }
        else {
            throw new IllegalArgumentException("Unexpected type: " + type);
        }
    }

    private static OptionalLong getIntegerValue(ConnectorSession session, Type type, Block block)
    {
        // works for BIGINT as well as for other integer types TINYINT/SMALLINT/INTEGER that store values as byte/short/int
        return block.isNull(0) ? OptionalLong.empty() : OptionalLong.of(((Number) type.getObjectValue(session, block, 0)).longValue());
    }

    private static OptionalDouble getDoubleValue(ConnectorSession session, Type type, Block block)
    {
        return block.isNull(0) ? OptionalDouble.empty() : OptionalDouble.of(((Number) type.getObjectValue(session, block, 0)).doubleValue());
    }

    private static Optional<LocalDate> getDateValue(ConnectorSession session, Type type, Block block)
    {
        return block.isNull(0) ? Optional.empty() : Optional.of(LocalDate.ofEpochDay(((SqlDate) type.getObjectValue(session, block, 0)).getDays()));
    }

    private static OptionalLong getTimestampValue(DateTimeZone timeZone, Block block)
    {
        // TODO #7122
        return block.isNull(0) ? OptionalLong.empty() : OptionalLong.of(MILLISECONDS.toSeconds(timeZone.convertUTCToLocal(block.getLong(0, 0))));
    }

    private static Optional<BigDecimal> getDecimalValue(ConnectorSession session, Type type, Block block)
    {
        return block.isNull(0) ? Optional.empty() : Optional.of(((SqlDecimal) type.getObjectValue(session, block, 0)).toBigDecimal());
    }

    private static Optional<Long> boxed(OptionalLong input)
    {
        return input.isPresent() ? Optional.of(input.getAsLong()) : Optional.empty();
    }

    private static Optional<Double> boxed(OptionalDouble input)
    {
        return input.isPresent() ? Optional.of(input.getAsDouble()) : Optional.empty();
    }

    public enum ReduceOperator
    {
        ADD,
        SUBTRACT,
        MIN,
        MAX,
    }

    public static class Range
    {
        private static final Range EMPTY = new Range(Optional.empty(), Optional.empty());

        private final Optional<? extends Comparable<?>> min;
        private final Optional<? extends Comparable<?>> max;

        public static Range empty()
        {
            return EMPTY;
        }

        public static Range create(Optional<? extends Comparable<?>> min, Optional<? extends Comparable<?>> max)
        {
            return new Range(min, max);
        }

        public static Range create(OptionalLong min, OptionalLong max)
        {
            return new Range(boxed(min), boxed(max));
        }

        public static Range create(OptionalDouble min, OptionalDouble max)
        {
            return new Range(boxed(min), boxed(max));
        }

        public Range(Optional<? extends Comparable<?>> min, Optional<? extends Comparable<?>> max)
        {
            this.min = requireNonNull(min, "min is null");
            this.max = requireNonNull(max, "max is null");
        }

        public Optional<? extends Comparable<?>> getMin()
        {
            return min;
        }

        public Optional<? extends Comparable<?>> getMax()
        {
            return max;
        }
    }
}
