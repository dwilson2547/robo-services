//! Bit fields in a CAN payload, numbered as DBC files number them (and as
//! dbc-forge's SignalDecoder does): bit `b` is bit `b % 8` of byte `b / 8`,
//! counted from the least significant bit.
//!
//! - Intel (little endian, `@1`): the start bit is the field's least significant
//!   bit; the field runs upward through the bits.
//! - Motorola (big endian, `@0`): the start bit is the field's most significant
//!   bit; the field runs down a byte (7..0) and on to the next byte's bit 7.

use serde::Serialize;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum ByteOrder {
    Intel,
    Motorola,
}

impl ByteOrder {
    pub const ALL: [ByteOrder; 2] = [ByteOrder::Intel, ByteOrder::Motorola];
}

fn bit_set(data: &[u8], bit: usize) -> bool {
    data.get(bit / 8).is_some_and(|b| b >> (bit % 8) & 1 == 1)
}

/// The absolute bit positions a field covers, most significant first for Motorola,
/// least significant first for Intel. None when it does not fit in `dlc` bytes.
pub fn covered(order: ByteOrder, start: usize, length: usize, dlc: usize) -> Option<Vec<usize>> {
    let mut bits = Vec::with_capacity(length);
    match order {
        ByteOrder::Intel => {
            for i in 0..length {
                bits.push(start + i);
            }
        }
        ByteOrder::Motorola => {
            let (mut byte, mut bit) = (start / 8, start % 8);
            for _ in 0..length {
                bits.push(byte * 8 + bit);
                if bit == 0 {
                    byte += 1;
                    bit = 7;
                } else {
                    bit -= 1;
                }
            }
        }
    }
    bits.iter().all(|&b| b / 8 < dlc).then_some(bits)
}

/// The raw value of a field (as covered() lists its bits), sign-extended when `signed`.
pub fn extract(data: &[u8], order: ByteOrder, bits: &[usize], signed: bool) -> i64 {
    let n = bits.len();
    let mut raw: u64 = 0;
    for (i, &b) in bits.iter().enumerate() {
        if bit_set(data, b) {
            let weight = match order {
                ByteOrder::Intel => i,
                ByteOrder::Motorola => n - 1 - i,
            };
            raw |= 1 << weight;
        }
    }
    if signed && n < 64 && raw >> (n - 1) & 1 == 1 {
        (raw as i64) - (1i64 << n)
    } else {
        raw as i64
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // Vectors checked against cantools (services/can_decode, which decodes the live
    // Impala DBC): EngineStatus RPM is 7|16@0+, and 0x840c decodes to raw 0x840c.
    #[test]
    fn motorola_16_from_bit_7_is_bytes_0_and_1() {
        let bits = covered(ByteOrder::Motorola, 7, 16, 8).unwrap();
        assert_eq!(bits[0], 7);
        assert_eq!(bits[15], 8);
        assert_eq!(extract(&[0x84, 0x0c, 0, 0, 0, 0, 0, 0], ByteOrder::Motorola, &bits, false), 0x840c);
    }

    #[test]
    fn motorola_from_bit_0_straddles_three_bytes() {
        // the bug in the LLM-drafted F-150 DBC: 0|16@0+ covers byte 0 bit 0, byte 1, byte 2 bits 7..1
        let bits = covered(ByteOrder::Motorola, 0, 16, 8).unwrap();
        assert_eq!((bits[0], bits[1], bits[15]), (0, 15, 17));
    }

    #[test]
    fn intel_16_from_bit_0_is_little_endian_bytes_0_and_1() {
        let bits = covered(ByteOrder::Intel, 0, 16, 8).unwrap();
        assert_eq!(extract(&[0x34, 0x12], ByteOrder::Intel, &bits, false), 0x1234);
    }

    #[test]
    fn signed_fields_are_sign_extended() {
        let bits = covered(ByteOrder::Intel, 0, 8, 1).unwrap();
        assert_eq!(extract(&[0xff], ByteOrder::Intel, &bits, true), -1);
        assert_eq!(extract(&[0xff], ByteOrder::Intel, &bits, false), 255);
    }

    #[test]
    fn fields_past_the_dlc_do_not_fit() {
        assert!(covered(ByteOrder::Intel, 4, 8, 1).is_none());
        assert!(covered(ByteOrder::Motorola, 7, 16, 1).is_none());
        assert!(covered(ByteOrder::Motorola, 7, 8, 1).is_some());
    }
}
