package io.github.dwilson2547.roboservices.derivations.iggysupport;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.flink.api.connector.source.Boundedness;
import org.apache.flink.api.connector.source.ReaderOutput;
import org.apache.flink.api.connector.source.Source;
import org.apache.flink.api.connector.source.SourceEvent;
import org.apache.flink.api.connector.source.SourceReader;
import org.apache.flink.api.connector.source.SourceReaderContext;
import org.apache.flink.api.connector.source.SourceSplit;
import org.apache.flink.api.connector.source.SplitEnumerator;
import org.apache.flink.api.connector.source.SplitEnumeratorContext;
import org.apache.flink.core.io.InputStatus;
import org.apache.flink.core.io.SimpleVersionedSerializer;

/**
 * Wraps a Flink {@link Source} so an idle reader waits before polling again.
 *
 * <p>The Iggy connector (0.8.0, unchanged in 0.9.0) returns {@code NOTHING_AVAILABLE} from
 * {@code pollNext} but an already-completed future from {@code isAvailable}, so Flink re-polls
 * immediately and an empty topic becomes a tight request loop against the Iggy server that pins the
 * taskmanager CPU. This wrapper delays availability by {@code idleBackoff} after an empty poll;
 * everything else passes straight through to the delegate, including split state and checkpoints.
 */
public final class IdleBackoffSource<T, SplitT extends SourceSplit, EnumChkT>
        implements Source<T, SplitT, EnumChkT> {

    private static final long serialVersionUID = 1L;

    public static final Duration DEFAULT_IDLE_BACKOFF = Duration.ofMillis(100);

    private final Source<T, SplitT, EnumChkT> delegate;
    private final long idleBackoffMillis;

    private IdleBackoffSource(Source<T, SplitT, EnumChkT> delegate, Duration idleBackoff) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (idleBackoff.isNegative() || idleBackoff.isZero()) {
            throw new IllegalArgumentException("idleBackoff must be positive");
        }
        this.idleBackoffMillis = idleBackoff.toMillis();
    }

    public static <T, SplitT extends SourceSplit, EnumChkT> IdleBackoffSource<T, SplitT, EnumChkT> wrap(
            Source<T, SplitT, EnumChkT> delegate) {
        return new IdleBackoffSource<>(delegate, DEFAULT_IDLE_BACKOFF);
    }

    public static <T, SplitT extends SourceSplit, EnumChkT> IdleBackoffSource<T, SplitT, EnumChkT> wrap(
            Source<T, SplitT, EnumChkT> delegate, Duration idleBackoff) {
        return new IdleBackoffSource<>(delegate, idleBackoff);
    }

    @Override
    public Boundedness getBoundedness() {
        return delegate.getBoundedness();
    }

    @Override
    public SplitEnumerator<SplitT, EnumChkT> createEnumerator(SplitEnumeratorContext<SplitT> context)
            throws Exception {
        return delegate.createEnumerator(context);
    }

    @Override
    public SplitEnumerator<SplitT, EnumChkT> restoreEnumerator(
            SplitEnumeratorContext<SplitT> context, EnumChkT checkpoint) throws Exception {
        return delegate.restoreEnumerator(context, checkpoint);
    }

    @Override
    public SimpleVersionedSerializer<SplitT> getSplitSerializer() {
        return delegate.getSplitSerializer();
    }

    @Override
    public SimpleVersionedSerializer<EnumChkT> getEnumeratorCheckpointSerializer() {
        return delegate.getEnumeratorCheckpointSerializer();
    }

    @Override
    public SourceReader<T, SplitT> createReader(SourceReaderContext readerContext) throws Exception {
        return new Reader<>(delegate.createReader(readerContext), idleBackoffMillis);
    }

    static final class Reader<T, SplitT extends SourceSplit> implements SourceReader<T, SplitT> {

        private final SourceReader<T, SplitT> delegate;
        private final long idleBackoffMillis;
        private boolean idle;

        Reader(SourceReader<T, SplitT> delegate, long idleBackoffMillis) {
            this.delegate = delegate;
            this.idleBackoffMillis = idleBackoffMillis;
        }

        @Override
        public void start() {
            delegate.start();
        }

        @Override
        public InputStatus pollNext(ReaderOutput<T> output) throws Exception {
            InputStatus status = delegate.pollNext(output);
            idle = status == InputStatus.NOTHING_AVAILABLE;
            return status;
        }

        @Override
        public CompletableFuture<Void> isAvailable() {
            if (!idle) {
                return delegate.isAvailable();
            }
            // Flink waits on this future through the task mailbox, so checkpoints and timers still
            // run during the backoff; only the next pollNext is deferred.
            return CompletableFuture.runAsync(
                    () -> {}, CompletableFuture.delayedExecutor(idleBackoffMillis, TimeUnit.MILLISECONDS));
        }

        @Override
        public List<SplitT> snapshotState(long checkpointId) {
            return delegate.snapshotState(checkpointId);
        }

        @Override
        public void addSplits(List<SplitT> splits) {
            idle = false;
            delegate.addSplits(splits);
        }

        @Override
        public void notifyNoMoreSplits() {
            idle = false;
            delegate.notifyNoMoreSplits();
        }

        @Override
        public void handleSourceEvents(SourceEvent sourceEvent) {
            delegate.handleSourceEvents(sourceEvent);
        }

        @Override
        public void pauseOrResumeSplits(Collection<String> splitsToPause, Collection<String> splitsToResume) {
            delegate.pauseOrResumeSplits(splitsToPause, splitsToResume);
        }

        @Override
        public void notifyCheckpointComplete(long checkpointId) throws Exception {
            delegate.notifyCheckpointComplete(checkpointId);
        }

        @Override
        public void notifyCheckpointAborted(long checkpointId) throws Exception {
            delegate.notifyCheckpointAborted(checkpointId);
        }

        @Override
        public void close() throws Exception {
            delegate.close();
        }
    }
}
