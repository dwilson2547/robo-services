//! Open sessions, buffered until they finish, and the offsets that are safe to store.
//!
//! A session finishes 2 minutes (END_QUIET_S) after its session_end with nothing new,
//! or after 30 minutes (IDLE_S) with nothing new and no end. Each topic's safe offset
//! stays below the first message of its oldest open session there (as vehicle-state
//! does it), so a restart replays every session not yet analysed.

use std::collections::{HashMap, HashSet};

use crate::records::{Key, Sample};

pub const KINDS: [&str; 4] = ["discovery", "gps", "imu", "session"];

#[derive(Debug, Default)]
pub struct SessionBuf {
    /// per CAN id: (time ms, payload, dlc), in arrival order
    pub can: HashMap<u32, Vec<(i64, [u8; 8], u8)>>,
    pub can_frames: usize,
    /// past MAX_CAN_FRAMES: counted, not kept
    pub can_dropped: usize,
    pub gps: Vec<(i64, f64)>,
    pub gyro: Vec<(i64, [f64; 3])>,
    pub ended: bool,
    last_seen: f64,
    first_offset: HashMap<&'static str, u64>,
}

pub struct Table {
    pub max_can_frames: usize,
    pub end_quiet_s: f64,
    pub idle_s: f64,
    sessions: HashMap<Key, SessionBuf>,
    done: HashSet<Key>,
    last_offset: HashMap<&'static str, u64>,
    pub late: usize,
}

impl Table {
    pub fn new(max_can_frames: usize, end_quiet_s: f64, idle_s: f64) -> Self {
        Self {
            max_can_frames, end_quiet_s, idle_s,
            sessions: HashMap::new(), done: HashSet::new(), last_offset: HashMap::new(), late: 0,
        }
    }

    pub fn open(&self) -> usize {
        self.sessions.len()
    }

    /// A message that is not a session's (no key, unreadable): only its offset counts.
    pub fn skip(&mut self, kind: &'static str, offset: u64) {
        self.last_offset.insert(kind, offset);
    }

    pub fn ingest(&mut self, kind: &'static str, offset: u64, key: Key, sample: Sample, now: f64) {
        self.last_offset.insert(kind, offset);
        if self.done.contains(&key) {
            self.late += 1; // arrived after the session was analysed (v1: ignored)
            return;
        }
        let max = self.max_can_frames;
        let s = self.sessions.entry(key).or_default();
        s.first_offset.entry(kind).or_insert(offset);
        s.last_seen = now;
        match sample {
            Sample::Can { t, id, dlc, data } => {
                if s.can_frames >= max {
                    s.can_dropped += 1;
                } else {
                    s.can_frames += 1;
                    s.can.entry(id).or_default().push((t, data, dlc));
                }
            }
            Sample::Gps { t, speed_kph } => s.gps.push((t, speed_kph)),
            Sample::Gyro { t, deg_s } => s.gyro.push((t, deg_s)),
            Sample::SessionEnd => s.ended = true,
            Sample::SessionOther => {}
        }
    }

    /// Sessions that have finished; they leave the table.
    pub fn take_finished(&mut self, now: f64) -> Vec<(Key, SessionBuf)> {
        let keys: Vec<Key> = self
            .sessions
            .iter()
            .filter(|(_, s)| {
                let quiet = now - s.last_seen;
                (s.ended && quiet >= self.end_quiet_s) || quiet >= self.idle_s
            })
            .map(|(k, _)| k.clone())
            .collect();
        keys.into_iter()
            .map(|k| {
                let s = self.sessions.remove(&k).unwrap();
                self.done.insert(k.clone());
                (k, s)
            })
            .collect()
    }

    /// The offset safe to store for a topic: everything up to it belongs to analysed
    /// sessions or none. None when nothing is safe yet.
    pub fn safe_offset(&self, kind: &'static str) -> Option<u64> {
        let oldest_open = self.sessions.values().filter_map(|s| s.first_offset.get(kind)).min();
        match oldest_open {
            Some(&first) => first.checked_sub(1),
            None => self.last_offset.get(kind).copied(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key(s: &str) -> Key {
        (s.to_string(), "phone".to_string())
    }

    fn can(t: i64) -> Sample {
        Sample::Can { t, id: 0x202, dlc: 8, data: [0; 8] }
    }

    #[test]
    fn a_session_finishes_after_its_end_and_two_quiet_minutes() {
        let mut t = Table::new(100, 120.0, 1800.0);
        t.ingest("discovery", 10, key("a"), can(0), 0.0);
        t.ingest("session", 3, key("a"), Sample::SessionEnd, 5.0);
        assert!(t.take_finished(100.0).is_empty());
        let done = t.take_finished(125.0);
        assert_eq!(done.len(), 1);
        assert!(done[0].1.ended);
        t.ingest("discovery", 11, key("a"), can(1), 130.0);
        assert_eq!((t.late, t.open()), (1, 0)); // late, not a new session
    }

    #[test]
    fn a_session_with_no_end_finishes_after_the_idle_time() {
        let mut t = Table::new(100, 120.0, 1800.0);
        t.ingest("gps", 1, key("a"), Sample::Gps { t: 0, speed_kph: 1.0 }, 0.0);
        assert!(t.take_finished(1799.0).is_empty());
        assert_eq!(t.take_finished(1800.0).len(), 1);
    }

    #[test]
    fn stored_offsets_stay_below_the_oldest_open_session() {
        let mut t = Table::new(100, 120.0, 1800.0);
        t.skip("discovery", 4);
        assert_eq!(t.safe_offset("discovery"), Some(4));
        t.ingest("discovery", 5, key("a"), can(0), 0.0);
        t.ingest("discovery", 6, key("b"), can(0), 50.0);
        t.ingest("discovery", 7, key("a"), can(1), 1.0);
        assert_eq!(t.safe_offset("discovery"), Some(4));
        t.ingest("session", 2, key("a"), Sample::SessionEnd, 1.0);
        t.take_finished(130.0); // a finishes, b (seen at 50) has not ended
        assert_eq!(t.safe_offset("discovery"), Some(5));
        assert_eq!(t.safe_offset("gps"), None);
    }

    #[test]
    fn frames_past_the_cap_are_counted_not_kept() {
        let mut t = Table::new(2, 120.0, 1800.0);
        for i in 0..5 {
            t.ingest("discovery", i, key("a"), can(i as i64), 0.0);
        }
        let (_, s) = t.take_finished(1800.0).pop().unwrap();
        assert_eq!((s.can_frames, s.can_dropped, s.can[&0x202].len()), (2, 3, 2));
    }
}
