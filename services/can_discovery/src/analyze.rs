//! One finished session -> its inventory and signal candidates (the JSON stored in
//! the registry's session_discoveries).

use rayon::prelude::*;
use serde::Serialize;
use serde_json::{json, Value};

use crate::correlate::{correlate, Candidate, Params};
use crate::inventory::{inventory, IdInventory};
use crate::reference::channels;
use crate::session::SessionBuf;

pub const VERSION: i32 = 1;

#[derive(Debug, Serialize)]
pub struct IdCandidates {
    pub can_id: u32,
    pub reference: &'static str,
    pub candidates: Vec<Candidate>,
}

pub struct Analysis {
    pub can_frames: usize,
    pub inventory: Value,
    pub candidates: Value,
    pub ids: usize,
    pub found: usize,
}

pub fn analyze(mut s: SessionBuf, p: &Params) -> Analysis {
    for frames in s.can.values_mut() {
        frames.sort_by_key(|f| f.0); // topics are read in order, but the phone's clock decides
    }
    s.gps.sort_by_key(|g| g.0);
    s.gyro.sort_by_key(|g| g.0);
    let (t0, t1) = s
        .can
        .values()
        .flat_map(|f| [f.first().map(|x| x.0), f.last().map(|x| x.0)])
        .flatten()
        .fold((i64::MAX, i64::MIN), |(lo, hi), t| (lo.min(t), hi.max(t)));
    let duration_s = if t1 > t0 { (t1 - t0) as f64 / 1000.0 } else { 0.0 };
    let refs = channels(&s.gps, &s.gyro);

    let mut ids: Vec<u32> = s.can.keys().copied().collect();
    ids.sort_unstable();
    let per_id: Vec<(IdInventory, Vec<IdCandidates>)> = ids
        .par_iter()
        .map(|&id| {
            let frames = &s.can[&id];
            let inv = inventory(id, frames, duration_s);
            let mut found = Vec::new();
            if !inv.is_static() {
                let payloads: Vec<(i64, [u8; 8])> = frames.iter().map(|f| (f.0, f.1)).collect();
                for (name, series) in &refs.named {
                    let (_, candidates) = correlate(&payloads, series, inv.dlc, p);
                    if !candidates.is_empty() {
                        found.push(IdCandidates { can_id: id, reference: name, candidates });
                    }
                }
            }
            (inv, found)
        })
        .collect();

    let found: Vec<&IdCandidates> = per_id.iter().flat_map(|(_, c)| c).collect();
    Analysis {
        can_frames: s.can_frames + s.can_dropped,
        ids: per_id.len(),
        found: found.len(),
        inventory: json!({
            "duration_s": duration_s,
            "can_frames_kept": s.can_frames,
            "can_frames_dropped": s.can_dropped,
            "gps_fixes": s.gps.len(),
            "imu_samples": s.gyro.len(),
            "references": refs.named.iter().map(|(n, v)| (n.to_string(), json!(v.len()))).collect::<serde_json::Map<_, _>>(),
            "ids": per_id.iter().map(|(i, _)| i).collect::<Vec<_>>(),
        }),
        candidates: json!(found),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// `cargo test --release -- --ignored --nocapture`: a 10 minute drive, 40 busy ids.
    #[test]
    #[ignore]
    fn timing_of_a_ten_minute_drive() {
        let mut s = SessionBuf::default();
        for id in 0..40u32 {
            let frames = (0..6000)
                .map(|i| {
                    let t = i as i64 * 100;
                    let v = ((t as f64 / 3000.0 + id as f64).sin() * 30000.0 + 32768.0) as u16;
                    let mut d = [id as u8, (i % 256) as u8, 0, 0, 0, 0, 0, 0];
                    d[2..4].copy_from_slice(&v.to_be_bytes());
                    d[5] = (t / 7 % 251) as u8;
                    (t, d, 8u8)
                })
                .collect();
            s.can.insert(id, frames);
        }
        s.can_frames = 240_000;
        s.gps = (0..600).map(|i| (i * 1000, 40.0 + (i as f64 / 30.0).sin() * 30.0)).collect();
        s.gyro = (0..60_000).map(|i| (i * 10, [0.0, (i as f64 / 800.0).sin() * 10.0, 0.1])).collect();
        let began = std::time::Instant::now();
        let a = analyze(s, &Params::default());
        println!("{} ids, {} id/reference pairs with candidates, {:.2}s", a.ids, a.found, began.elapsed().as_secs_f64());
    }
}
