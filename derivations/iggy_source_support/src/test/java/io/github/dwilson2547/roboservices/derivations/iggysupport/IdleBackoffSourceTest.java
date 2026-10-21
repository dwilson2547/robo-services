package io.github.dwilson2547.roboservices.derivations.iggysupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.flink.api.connector.source.ReaderOutput;
import org.apache.flink.api.connector.source.SourceOutput;
import org.apache.flink.api.connector.source.SourceReader;
import org.apache.flink.api.connector.source.SourceSplit;
import org.apache.flink.api.common.eventtime.Watermark;
import org.apache.flink.core.io.InputStatus;
import org.junit.jupiter.api.Test;

class IdleBackoffSourceTest {

    @Test
    void passesThroughAvailabilityWhileDataIsFlowing() throws Exception {
        FakeReader delegate = new FakeReader(InputStatus.MORE_AVAILABLE);
        IdleBackoffSource.Reader<String, SourceSplit> reader = new IdleBackoffSource.Reader<>(delegate, 10_000);

        assertEquals(InputStatus.MORE_AVAILABLE, reader.pollNext(new NoopOutput()));
        assertTrue(reader.isAvailable().isDone());
    }

    @Test
    void delaysAvailabilityAfterEmptyPoll() throws Exception {
        FakeReader delegate = new FakeReader(InputStatus.NOTHING_AVAILABLE);
        IdleBackoffSource.Reader<String, SourceSplit> reader = new IdleBackoffSource.Reader<>(delegate, 200);

        assertEquals(InputStatus.NOTHING_AVAILABLE, reader.pollNext(new NoopOutput()));
        long start = System.nanoTime();
        CompletableFuture<Void> available = reader.isAvailable();
        assertFalse(available.isDone());
        available.get(5, TimeUnit.SECONDS);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) >= 150);
    }

    @Test
    void resumesImmediatePollingOnceDataReturns() throws Exception {
        FakeReader delegate = new FakeReader(InputStatus.NOTHING_AVAILABLE, InputStatus.MORE_AVAILABLE);
        IdleBackoffSource.Reader<String, SourceSplit> reader = new IdleBackoffSource.Reader<>(delegate, 10_000);

        reader.pollNext(new NoopOutput());
        assertFalse(reader.isAvailable().isDone());
        reader.pollNext(new NoopOutput());
        assertTrue(reader.isAvailable().isDone());
    }

    @Test
    void newSplitsClearIdleState() throws Exception {
        FakeReader delegate = new FakeReader(InputStatus.NOTHING_AVAILABLE);
        IdleBackoffSource.Reader<String, SourceSplit> reader = new IdleBackoffSource.Reader<>(delegate, 10_000);

        reader.pollNext(new NoopOutput());
        reader.addSplits(List.of());
        assertTrue(reader.isAvailable().isDone());
    }

    /** Returns the queued statuses in order, repeating the last; always reports itself available. */
    private static final class FakeReader implements SourceReader<String, SourceSplit> {
        private final Deque<InputStatus> statuses = new ArrayDeque<>();

        FakeReader(InputStatus... statuses) {
            this.statuses.addAll(List.of(statuses));
        }

        @Override
        public void start() {}

        @Override
        public InputStatus pollNext(ReaderOutput<String> output) {
            return statuses.size() > 1 ? statuses.poll() : statuses.peek();
        }

        @Override
        public List<SourceSplit> snapshotState(long checkpointId) {
            return List.of();
        }

        @Override
        public CompletableFuture<Void> isAvailable() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void addSplits(List<SourceSplit> splits) {}

        @Override
        public void notifyNoMoreSplits() {}

        @Override
        public void close() {}
    }

    private static final class NoopOutput implements ReaderOutput<String> {
        @Override
        public void collect(String record) {}

        @Override
        public void collect(String record, long timestamp) {}

        @Override
        public void emitWatermark(Watermark watermark) {}

        @Override
        public void markIdle() {}

        @Override
        public void markActive() {}

        @Override
        public SourceOutput<String> createOutputForSplit(String splitId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void releaseOutputForSplit(String splitId) {}
    }
}
