//! Brute-force signal discovery for one CAN id against one reference channel: a
//! port of embedded/dbc-forge's SignalCorrelator.kt, same behaviour.
//!
//! Candidate fields (start bit, length, byte order, signedness) are slid across the
//! id's frames. The frames (zero-order hold) and the reference (linear
//! interpolation) are put on a uniform grid; a lag search finds the alignment that
//! maximises |Pearson r| (absorbing clock offsets and transport jitter); a
//! least-squares fit of reference on raw gives the DBC factor and offset.
//!
//! One change from the Kotlin: among equally good fields of the same length, the one
//! whose low bits actually change wins. A field shifted down past the real one ends in
//! bits that never move (the top of the real field is rarely used, so both correlate
//! alike); a real signal's least significant bit moves.

use serde::Serialize;

use crate::bits::{covered, extract, ByteOrder};

#[derive(Debug, Clone)]
pub struct Params {
    pub lengths: Vec<usize>,
    pub grid_ms: i64,
    pub max_lag_ms: i64,
    pub threshold: f64,
    pub max_results: usize,
}

impl Default for Params {
    fn default() -> Self {
        Self { lengths: vec![8, 12, 16], grid_ms: 200, max_lag_ms: 2000, threshold: 0.8, max_results: 12 }
    }
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct Candidate {
    pub start_bit: usize,
    pub length: usize,
    pub byte_order: ByteOrder,
    pub signed: bool,
    /// signed Pearson r at the best lag (negative = inverse)
    pub r: f64,
    /// reference lag that maximised |r|: raw at t pairs with the reference at t + lag
    /// (negative when the CAN value trails the reference)
    pub lag_ms: i64,
    /// physical = raw * factor + offset
    pub factor: f64,
    pub offset: f64,
    pub samples: usize,
    /// least significant bits that never changed over the drive (0 for a well-aligned field)
    pub fixed_low_bits: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Status {
    Ok,
    NoFrames,
    NoReference,
    ReferenceStatic,
    NoOverlap,
    NoMatch,
}

/// `frames`: (time ms, payload) sorted by time; `reference`: (time ms, value) sorted by time.
pub fn correlate(frames: &[(i64, [u8; 8])], reference: &[(i64, f64)], dlc: usize, p: &Params) -> (Status, Vec<Candidate>) {
    if frames.len() < 5 || dlc == 0 {
        return (Status::NoFrames, vec![]);
    }
    if reference.len() < 5 {
        return (Status::NoReference, vec![]);
    }
    let ref_values: Vec<f64> = reference.iter().map(|r| r.1).collect();
    if variance(&ref_values) < 1e-6 {
        return (Status::ReferenceStatic, vec![]);
    }
    let t0 = frames[0].0.max(reference[0].0);
    let t1 = frames[frames.len() - 1].0.min(reference[reference.len() - 1].0);
    if t1 - t0 < p.grid_ms * 5 {
        return (Status::NoOverlap, vec![]);
    }
    let grid: Vec<i64> = (0..).map(|i| t0 + i * p.grid_ms).take_while(|&t| t <= t1).collect();
    let n = grid.len();
    let ref_grid: Vec<f64> = grid.iter().map(|&t| interp(reference, t)).collect();

    // zero-order hold: each slot takes the latest frame at or before its time
    let mut slot_frame: Vec<Option<usize>> = vec![None; n];
    let mut fi = 0;
    let mut last = None;
    for (i, &t) in grid.iter().enumerate() {
        while fi < frames.len() && frames[fi].0 <= t {
            last = Some(fi);
            fi += 1;
        }
        slot_frame[i] = last;
    }
    let Some(first) = slot_frame.iter().position(|s| s.is_some()) else {
        return (Status::NoOverlap, vec![]);
    };
    if n - first < 5 {
        return (Status::NoOverlap, vec![]);
    }
    let held: Vec<&[u8; 8]> = slot_frame[first..].iter().map(|s| &frames[s.unwrap()].1).collect();
    let ref_grid = &ref_grid[first..];
    let m = held.len();
    let max_lag = (p.max_lag_ms / p.grid_ms) as isize;

    let mut results = Vec::new();
    let mut raw = vec![0f64; m];
    for &length in &p.lengths {
        if length > dlc * 8 {
            continue;
        }
        for order in ByteOrder::ALL {
            for start in 0..dlc * 8 {
                let Some(bits) = covered(order, start, length, dlc) else { continue };
                for signed in [false, true] {
                    for (i, data) in held.iter().enumerate() {
                        raw[i] = extract(&data[..], order, &bits, signed) as f64;
                    }
                    if variance(&raw) < 1e-9 {
                        continue;
                    }
                    let (mut best_r, mut best_lag) = (0.0f64, 0isize);
                    for lag in -max_lag..=max_lag {
                        let r = pearson_lagged(&raw, ref_grid, lag);
                        if r.abs() > best_r.abs() {
                            best_r = r;
                            best_lag = lag;
                        }
                    }
                    if best_r.abs() >= p.threshold {
                        let (factor, offset, count) = regress_lagged(&raw, ref_grid, best_lag);
                        if count >= 5 {
                            let first = raw[0] as i64;
                            let changed = raw.iter().fold(0i64, |acc, &v| acc | (v as i64 ^ first));
                            results.push(Candidate {
                                start_bit: start, length, byte_order: order, signed, r: best_r,
                                lag_ms: best_lag as i64 * p.grid_ms, factor, offset, samples: count,
                                fixed_low_bits: changed.trailing_zeros().min(length as u32),
                            });
                        }
                    }
                }
            }
        }
    }
    if results.is_empty() {
        return (Status::NoMatch, vec![]);
    }
    // rank by |r| bucketed to ~0.001, then prefer the wider field within a bucket: a
    // sub-slice that correlates as well as the whole field is the less useful decode
    results.sort_by(|a, b| {
        let (ka, kb) = ((a.r.abs() * 1000.0) as i64, (b.r.abs() * 1000.0) as i64);
        kb.cmp(&ka)
            .then(b.length.cmp(&a.length))
            .then(a.fixed_low_bits.cmp(&b.fixed_low_bits))
            .then(b.r.abs().total_cmp(&a.r.abs()))
            .then(a.start_bit.cmp(&b.start_bit))
    });
    (Status::Ok, suppress_overlaps(results, dlc, p.max_results))
}

/// Keep the best candidate among any that share more than half their bits.
fn suppress_overlaps(ranked: Vec<Candidate>, dlc: usize, max_results: usize) -> Vec<Candidate> {
    let mut kept: Vec<Candidate> = Vec::new();
    let mut kept_bits: Vec<Vec<usize>> = Vec::new();
    for c in ranked {
        let bits = covered(c.byte_order, c.start_bit, c.length, dlc).unwrap_or_default();
        let clashes = kept_bits.iter().any(|prev| {
            let inter = bits.iter().filter(|b| prev.contains(b)).count();
            inter > bits.len().min(prev.len()) / 2
        });
        if !clashes {
            kept.push(c);
            kept_bits.push(bits);
        }
        if kept.len() >= max_results {
            break;
        }
    }
    kept
}

pub fn variance(a: &[f64]) -> f64 {
    let n = a.len();
    if n < 2 {
        return 0.0;
    }
    let mean = a.iter().sum::<f64>() / n as f64;
    a.iter().map(|x| (x - mean).powi(2)).sum::<f64>() / n as f64
}

/// Index range pairing raw[i] with reference[i + lag].
fn lagged_range(n: usize, lag: isize) -> (usize, usize) {
    let start = (-lag).max(0) as usize;
    let end = (n as isize - lag).min(n as isize).max(0) as usize; // exclusive
    (start, end.max(start))
}

fn pearson_lagged(raw: &[f64], reference: &[f64], lag: isize) -> f64 {
    let (lo, hi) = lagged_range(raw.len(), lag);
    let count = hi - lo;
    if count < 3 {
        return 0.0;
    }
    let at = |i: usize| reference[(i as isize + lag) as usize];
    let (mut mx, mut my) = (0.0, 0.0);
    for i in lo..hi {
        mx += raw[i];
        my += at(i);
    }
    mx /= count as f64;
    my /= count as f64;
    let (mut num, mut sx, mut sy) = (0.0, 0.0, 0.0);
    for i in lo..hi {
        let (dx, dy) = (raw[i] - mx, at(i) - my);
        num += dx * dy;
        sx += dx * dx;
        sy += dy * dy;
    }
    let denom = sx.sqrt() * sy.sqrt();
    if denom < 1e-9 { 0.0 } else { num / denom }
}

/// Least squares of reference on raw at `lag`: (slope, intercept, samples).
fn regress_lagged(raw: &[f64], reference: &[f64], lag: isize) -> (f64, f64, usize) {
    let (lo, hi) = lagged_range(raw.len(), lag);
    let count = hi - lo;
    if count < 2 {
        return (0.0, 0.0, count);
    }
    let at = |i: usize| reference[(i as isize + lag) as usize];
    let (mut mx, mut my) = (0.0, 0.0);
    for i in lo..hi {
        mx += raw[i];
        my += at(i);
    }
    mx /= count as f64;
    my /= count as f64;
    let (mut sxx, mut sxy) = (0.0, 0.0);
    for i in lo..hi {
        let (dx, dy) = (raw[i] - mx, at(i) - my);
        sxx += dx * dx;
        sxy += dx * dy;
    }
    let slope = if sxx < 1e-9 { 0.0 } else { sxy / sxx };
    (slope, my - slope * mx, count)
}

/// Linear interpolation of a sorted series at `t`, clamped at the ends.
pub fn interp(series: &[(i64, f64)], t: i64) -> f64 {
    let (first, last) = (series[0], series[series.len() - 1]);
    if t <= first.0 {
        return first.1;
    }
    if t >= last.0 {
        return last.1;
    }
    let hi = series.partition_point(|p| p.0 <= t);
    let (a, b) = (series[hi - 1], series[hi]);
    if b.0 == a.0 {
        return a.1;
    }
    a.1 + (t - a.0) as f64 / (b.0 - a.0) as f64 * (b.1 - a.1)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A 10 minute drive: speed in km/h rising and falling.
    fn speed_at(t_ms: i64) -> f64 {
        let s = t_ms as f64 / 1000.0;
        40.0 + 35.0 * (s / 37.0).sin() + 10.0 * (s / 11.0).sin()
    }

    /// Frames at 50 Hz carrying speed / 0.01 big endian in bytes 3-4 (Motorola 31|16),
    /// sent `delay_ms` after the reference sees it, plus a counter in byte 7.
    fn frames(delay_ms: i64) -> Vec<(i64, [u8; 8])> {
        (0..30_000)
            .map(|i| {
                let t = i * 20;
                let raw = (speed_at(t - delay_ms) / 0.01).round() as u16;
                let mut d = [0x5au8, 0, 0, 0, 0, 0, 0, 0];
                d[3..5].copy_from_slice(&raw.to_be_bytes());
                d[7] = (i % 256) as u8;
                (t, d)
            })
            .collect()
    }

    fn gps() -> Vec<(i64, f64)> {
        (0..600).map(|s| (s * 1000, speed_at(s * 1000))).collect()
    }

    #[test]
    fn finds_a_big_endian_speed_field_with_its_scale_and_lag() {
        let (status, cands) = correlate(&frames(400), &gps(), 8, &Params::default());
        assert_eq!(status, Status::Ok);
        let best = &cands[0];
        assert_eq!((best.start_bit, best.length, best.byte_order, best.signed), (31, 16, ByteOrder::Motorola, false));
        assert!(best.r > 0.99, "r = {}", best.r);
        assert_eq!(best.fixed_low_bits, 0);
        assert!((best.factor - 0.01).abs() < 0.0005, "factor = {}", best.factor);
        assert!(best.offset.abs() < 1.0, "offset = {}", best.offset); // 1 Hz GPS interpolated onto the grid
        assert_eq!(best.lag_ms, -400); // CAN trails the reference by 400 ms: raw[i] pairs with ref[i - 2]
        // the counter and the constant byte are not candidates
        assert!(cands.iter().all(|c| !covered(c.byte_order, c.start_bit, c.length, 8).unwrap().iter().any(|b| b / 8 == 7 || b / 8 == 0)));
    }

    #[test]
    fn static_reference_and_too_few_frames_say_why() {
        let parked: Vec<(i64, f64)> = (0..100).map(|s| (s * 1000, 0.0)).collect();
        assert_eq!(correlate(&frames(0), &parked, 8, &Params::default()).0, Status::ReferenceStatic);
        assert_eq!(correlate(&frames(0)[..3], &gps(), 8, &Params::default()).0, Status::NoFrames);
    }

    #[test]
    fn interp_is_linear_and_clamped() {
        let s = [(0, 0.0), (1000, 10.0)];
        assert_eq!(interp(&s, 250), 2.5);
        assert_eq!(interp(&s, -5), 0.0);
        assert_eq!(interp(&s, 2000), 10.0);
    }
}
