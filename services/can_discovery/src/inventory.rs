//! What a human needs first when reverse-engineering an id: how often it comes,
//! how long it is, and which bytes and bits move. Counters, checksums and constant
//! bytes are told apart here; the correlator only finds physical signals.

use std::collections::BTreeMap;

use serde::Serialize;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ByteClass {
    Constant,
    /// steps by the same amount (mod 256, or mod 16 in the low nibble) frame to frame
    Counter,
    Varying,
}

#[derive(Debug, Clone, Serialize)]
pub struct ByteStats {
    pub index: usize,
    pub class: ByteClass,
    pub distinct: usize,
    pub min: u8,
    pub max: u8,
}

#[derive(Debug, Clone, Serialize)]
pub struct IdInventory {
    pub can_id: u32,
    pub count: usize,
    pub rate_hz: f64,
    pub median_period_ms: Option<f64>,
    pub dlc: usize,
    pub bytes: Vec<ByteStats>,
    /// per bit (DBC numbering, byte * 8 + bit): fraction of frame-to-frame transitions that flip it
    pub bit_flip_rate: Vec<f64>,
    /// per bit: Shannon entropy of its value over the drive, 0 (fixed) to 1 (half the time set)
    pub bit_entropy: Vec<f64>,
}

impl IdInventory {
    /// Every byte constant: nothing for the correlator to find.
    pub fn is_static(&self) -> bool {
        self.bytes.iter().all(|b| b.class == ByteClass::Constant)
    }
}

/// `frames`: one id's (time ms, payload, dlc), sorted by time; `duration_s`: the session's span.
pub fn inventory(can_id: u32, frames: &[(i64, [u8; 8], u8)], duration_s: f64) -> IdInventory {
    let count = frames.len();
    let mut dlc_counts: BTreeMap<u8, usize> = BTreeMap::new();
    for f in frames {
        *dlc_counts.entry(f.2).or_default() += 1;
    }
    let dlc = dlc_counts.iter().max_by_key(|(d, c)| (**c, **d)).map(|(d, _)| *d as usize).unwrap_or(0).min(8);

    let mut periods: Vec<i64> = frames.windows(2).map(|w| w[1].0 - w[0].0).collect();
    periods.sort_unstable();
    let median_period_ms = (!periods.is_empty()).then(|| periods[periods.len() / 2] as f64);

    let bytes = (0..dlc).map(|i| byte_stats(i, frames)).collect();
    let bits = dlc * 8;
    let mut flips = vec![0usize; bits];
    let mut ones = vec![0usize; bits];
    for (k, f) in frames.iter().enumerate() {
        for b in 0..bits {
            let v = f.1[b / 8] >> (b % 8) & 1;
            ones[b] += v as usize;
            if k > 0 && v != frames[k - 1].1[b / 8] >> (b % 8) & 1 {
                flips[b] += 1;
            }
        }
    }
    let transitions = count.saturating_sub(1).max(1) as f64;
    IdInventory {
        can_id,
        count,
        rate_hz: if duration_s > 0.0 { count as f64 / duration_s } else { 0.0 },
        median_period_ms,
        dlc,
        bytes,
        bit_flip_rate: flips.iter().map(|&f| round3(f as f64 / transitions)).collect(),
        bit_entropy: ones.iter().map(|&o| round3(entropy(o as f64 / count.max(1) as f64))).collect(),
    }
}

fn byte_stats(index: usize, frames: &[(i64, [u8; 8], u8)]) -> ByteStats {
    let values: Vec<u8> = frames.iter().map(|f| f.1[index]).collect();
    let mut seen = [false; 256];
    for &v in &values {
        seen[v as usize] = true;
    }
    let distinct = seen.iter().filter(|s| **s).count();
    let (min, max) = (*values.iter().min().unwrap_or(&0), *values.iter().max().unwrap_or(&0));
    let class = if distinct <= 1 {
        ByteClass::Constant
    } else if is_counter(&values, 0xff) || is_counter(&values.iter().map(|v| v & 0x0f).collect::<Vec<_>>(), 0x0f) {
        ByteClass::Counter
    } else {
        ByteClass::Varying
    };
    ByteStats { index, class, distinct, min, max }
}

/// At least 90% of steps are the same non-zero step, modulo mask + 1.
fn is_counter(values: &[u8], mask: u8) -> bool {
    if values.len() < 8 {
        return false;
    }
    let mut steps: BTreeMap<u8, usize> = BTreeMap::new();
    for w in values.windows(2) {
        *steps.entry(w[1].wrapping_sub(w[0]) & mask).or_default() += 1;
    }
    let (step, n) = steps.iter().max_by_key(|(_, n)| **n).map(|(s, n)| (*s, *n)).unwrap();
    step != 0 && n * 10 >= (values.len() - 1) * 9
}

fn entropy(p: f64) -> f64 {
    if p <= 0.0 || p >= 1.0 {
        0.0
    } else {
        -(p * p.log2() + (1.0 - p) * (1.0 - p).log2())
    }
}

fn round3(x: f64) -> f64 {
    (x * 1000.0).round() / 1000.0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classifies_constant_counter_nibble_counter_and_varying_bytes() {
        let frames: Vec<(i64, [u8; 8], u8)> = (0..500u32)
            .map(|i| {
                let speed = ((i as f64 / 20.0).sin() * 100.0 + 120.0) as u8;
                (i as i64 * 20, [0x5a, i as u8, 0xa0 | (i as u8 & 0x0f), speed, 0, 0, 0, 0], 4)
            })
            .collect();
        let inv = inventory(0x202, &frames, 10.0);
        assert_eq!(inv.dlc, 4);
        assert_eq!(inv.rate_hz, 50.0);
        assert_eq!(inv.median_period_ms, Some(20.0));
        let classes: Vec<ByteClass> = inv.bytes.iter().map(|b| b.class).collect();
        assert_eq!(classes, [ByteClass::Constant, ByteClass::Counter, ByteClass::Counter, ByteClass::Varying]);
        assert_eq!(inv.bit_flip_rate[8], 1.0); // a counter's lowest bit flips every frame
        assert_eq!(inv.bit_entropy[0], 0.0); // constant
        assert!(!inv.is_static());
    }

    #[test]
    fn an_id_that_never_changes_is_static() {
        let frames: Vec<(i64, [u8; 8], u8)> = (0..50).map(|i| (i * 100, [1, 2, 3, 0, 0, 0, 0, 0], 3)).collect();
        assert!(inventory(0x80, &frames, 5.0).is_static());
    }
}
