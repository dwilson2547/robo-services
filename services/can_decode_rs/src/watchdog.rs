//! Port of can_pub_sub_probe.consumer_watchdog.ProgressWatchdog: pure stall
//! detection. apache-iggy consumers do not rejoin their group after an Iggy
//! server restart, so the process watches progress instead — topic message
//! count vs the last consumed offset — and exits non-zero so Kubernetes
//! restarts it from the committed offset.

#[derive(Debug, Default)]
pub struct ProgressWatchdog {
    pub stall_after: f64,
    pub max_failures: u32,
    offset: Option<u64>,
    count_at_progress: Option<u64>,
    behind_since: Option<f64>,
    failures: u32,
}

impl ProgressWatchdog {
    pub fn new(stall_after: f64, max_failures: u32) -> Self {
        Self {
            stall_after,
            max_failures,
            ..Default::default()
        }
    }

    /// Record one reading; returns why the consumer is stalled, or None.
    pub fn observe(&mut self, messages_count: u64, consumed_offset: Option<u64>, now: f64) -> Option<String> {
        self.failures = 0;
        if self.count_at_progress.is_none() || consumed_offset != self.offset {
            self.offset = consumed_offset;
            self.count_at_progress = Some(messages_count);
            self.behind_since = None;
            return None;
        }
        if messages_count <= self.count_at_progress.unwrap_or(0) {
            return None;
        }
        if self.behind_since.is_none() {
            self.behind_since = Some(now);
        }
        if now - self.behind_since.unwrap() >= self.stall_after {
            return Some(format!(
                "topic grew from {} to {messages_count} messages but the consumed offset stayed at {consumed_offset:?} for {:.0}s",
                self.count_at_progress.unwrap(),
                now - self.behind_since.unwrap()
            ));
        }
        None
    }

    /// Record a failed reading; returns why the consumer is stalled, or None.
    pub fn failed(&mut self) -> Option<String> {
        self.failures += 1;
        if self.failures >= self.max_failures {
            return Some(format!("reading the topic failed {} times in a row", self.failures));
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn idle_topic_never_stalls() {
        let mut dog = ProgressWatchdog::new(120.0, 4);
        for t in 0..100 {
            assert!(dog.observe(50, Some(49), t as f64 * 30.0).is_none());
        }
    }

    #[test]
    fn stalls_when_topic_grows_but_offset_frozen() {
        let mut dog = ProgressWatchdog::new(120.0, 4);
        assert!(dog.observe(100, Some(99), 0.0).is_none());
        assert!(dog.observe(110, Some(99), 60.0).is_none()); // behind since t=60
        assert!(dog.observe(110, Some(99), 179.0).is_none()); // 119s behind
        assert!(dog.observe(110, Some(99), 181.0).is_some()); // 121s behind >= 120
    }

    #[test]
    fn progress_resets_the_timer() {
        let mut dog = ProgressWatchdog::new(120.0, 4);
        assert!(dog.observe(100, Some(99), 0.0).is_none());
        assert!(dog.observe(110, Some(99), 100.0).is_none()); // behind since t=100
        // offset moved: timer resets
        assert!(dog.observe(110, Some(105), 110.0).is_none());
        assert!(dog.observe(120, Some(105), 200.0).is_none()); // behind since t=200
        assert!(dog.observe(130, Some(105), 319.0).is_none()); // 119s behind
        assert!(dog.observe(130, Some(105), 321.0).is_some()); // 121s behind
    }

    #[test]
    fn failures_trip_after_max() {
        let mut dog = ProgressWatchdog::new(120.0, 3);
        assert!(dog.failed().is_none());
        assert!(dog.failed().is_none());
        assert!(dog.failed().is_some());
    }
}
