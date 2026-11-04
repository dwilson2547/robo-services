//! Ground-truth channels for the correlator, built from the phone's GPS and the
//! IMU. None needs to know how the phone or dongle is mounted:
//!
//! - speed_kph: GPS ground speed;
//! - long_accel_m_s2: its time derivative (between consecutive fixes);
//! - yaw_rate_deg_s: the gyro axis that varies most over the drive (yaw, in a car;
//!   its sign is arbitrary, which the correlator's signed r absorbs);
//! - lat_accel_m_s2: speed x yaw rate.

use crate::correlate::{interp, variance};

pub const GPS_GAP_MS: i64 = 5_000;

pub struct Channels {
    pub named: Vec<(&'static str, Vec<(i64, f64)>)>,
}

/// `gps`: (time ms, ground speed km/h); `gyro`: (time ms, [x, y, z] deg/s); both sorted.
pub fn channels(gps: &[(i64, f64)], gyro: &[(i64, [f64; 3])]) -> Channels {
    let mut named = Vec::new();
    if gps.len() >= 2 {
        named.push(("speed_kph", gps.to_vec()));
        let accel: Vec<(i64, f64)> = gps
            .windows(2)
            .filter(|w| w[1].0 > w[0].0 && w[1].0 - w[0].0 <= GPS_GAP_MS)
            .map(|w| ((w[0].0 + w[1].0) / 2, (w[1].1 - w[0].1) / 3.6 / ((w[1].0 - w[0].0) as f64 / 1000.0)))
            .collect();
        if accel.len() >= 2 {
            named.push(("long_accel_m_s2", accel));
        }
    }
    if gyro.len() >= 2 {
        let axis = (0..3)
            .max_by(|&a, &b| {
                let va = variance(&gyro.iter().map(|g| g.1[a]).collect::<Vec<_>>());
                let vb = variance(&gyro.iter().map(|g| g.1[b]).collect::<Vec<_>>());
                va.total_cmp(&vb)
            })
            .unwrap();
        let yaw: Vec<(i64, f64)> = gyro.iter().map(|g| (g.0, g.1[axis])).collect();
        if gps.len() >= 2 {
            let lat = yaw.iter().map(|&(t, w)| (t, interp(gps, t) / 3.6 * w.to_radians())).collect();
            named.push(("yaw_rate_deg_s", yaw));
            named.push(("lat_accel_m_s2", lat));
        } else {
            named.push(("yaw_rate_deg_s", yaw));
        }
    }
    Channels { named }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn derives_acceleration_and_picks_the_busiest_gyro_axis() {
        let gps: Vec<(i64, f64)> = (0..10).map(|s| (s * 1000, s as f64 * 3.6)).collect(); // +1 m/s each second
        let gyro: Vec<(i64, [f64; 3])> = (0..100).map(|i| (i * 100, [0.1, (i as f64).sin() * 20.0, 0.2])).collect();
        let c = channels(&gps, &gyro);
        let names: Vec<&str> = c.named.iter().map(|n| n.0).collect();
        assert_eq!(names, ["speed_kph", "long_accel_m_s2", "yaw_rate_deg_s", "lat_accel_m_s2"]);
        assert!(c.named[1].1.iter().all(|a| (a.1 - 1.0).abs() < 1e-9));
        assert_eq!(c.named[2].1[5].1, (5f64).sin() * 20.0);
    }

    #[test]
    fn gps_gaps_give_no_acceleration_across_them() {
        let gps = [(0, 10.0), (1000, 20.0), (2000, 30.0), (20_000, 90.0)];
        let c = channels(&gps, &[]);
        assert_eq!(c.named[1].1.len(), 2); // two intervals, none across the 18 s gap
    }
}
