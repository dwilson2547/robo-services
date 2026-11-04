//! Minimal DBC parser: just enough of the format for the project's DBC files —
//! `BO_` messages and their `SG_` signals. Mirrors the quirks services/can_decode
//! handles in Python: phone-dbc-repo DBCs write 29-bit ids without the
//! 0x80000000 extended flag, so the flag is set on any id above 11 bits and
//! messages are keyed by the bare id (the dongle sends ids without a flag).

use std::collections::HashMap;

const EXTENDED_FLAG: u32 = 0x8000_0000;
const ID_MASK: u32 = 0x1FFF_FFFF;

#[derive(Debug, Clone)]
pub struct Signal {
    pub name: String,
    /// DBC start bit. For Intel (@1) this is the LSB in linear numbering
    /// (bit index = byte * 8 + bit-in-byte, bit 0 = LSB of byte 0). For
    /// Motorola (@0) it is the MSB in the same linear numbering.
    pub start: u32,
    pub length: u32,
    pub little_endian: bool,
    pub signed: bool,
    pub scale: f64,
    pub offset: f64,
}

#[derive(Debug, Clone)]
pub struct Message {
    pub name: String,
    /// Bare CAN id (extended flag stripped).
    pub id: u32,
    pub dlc: u8,
    pub signals: Vec<Signal>,
}

#[derive(Debug, Default)]
pub struct Dbc {
    /// {bare CAN id: message}
    pub messages: HashMap<u32, Message>,
}

/// Parse DBC text into {bare CAN id: message}.
pub fn load_dbc(text: &str) -> Result<Dbc, String> {
    let text = flag_extended_ids(text);
    let mut dbc = Dbc::default();
    let mut current: Option<Message> = None;

    for line in text.lines() {
        let trimmed = line.trim();
        if let Some(rest) = trimmed.strip_prefix("BO_ ") {
            if let Some(msg) = current.take() {
                dbc.messages.insert(msg.id, msg);
            }
            current = Some(parse_bo(rest)?);
        } else if let Some(rest) = trimmed.strip_prefix("SG_ ") {
            let msg = current
                .as_mut()
                .ok_or_else(|| format!("SG_ outside BO_: {trimmed}"))?;
            msg.signals.push(parse_sg(rest)?);
        }
    }
    if let Some(msg) = current.take() {
        dbc.messages.insert(msg.id, msg);
    }
    Ok(dbc)
}

/// Set the extended flag on any BO_ id above 11 bits (see module docs).
fn flag_extended_ids(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for line in text.split_inclusive('\n') {
        let trimmed = line.trim_start();
        if let Some(rest) = trimmed.strip_prefix("BO_ ") {
            if let Some((id_str, tail)) = rest.split_once(' ') {
                if let Ok(mut id) = id_str.parse::<u32>() {
                    if (0x7FF..EXTENDED_FLAG).contains(&id) {
                        id |= EXTENDED_FLAG;
                    }
                    let indent = &line[..line.len() - trimmed.len()];
                    out.push_str(indent);
                    out.push_str("BO_ ");
                    out.push_str(&id.to_string());
                    out.push(' ');
                    out.push_str(tail);
                    continue;
                }
            }
        }
        out.push_str(line);
    }
    out
}

/// `201 ECMEngineStatus: 7 PCM` -> Message (signals filled in later)
fn parse_bo(rest: &str) -> Result<Message, String> {
    let (head, _sender) = rest
        .split_once(':')
        .ok_or_else(|| format!("BO_ without ':': {rest}"))?;
    let mut parts = head.split_whitespace();
    let id: u32 = parts
        .next()
        .and_then(|s| s.parse().ok())
        .ok_or_else(|| format!("BO_ without id: {rest}"))?;
    let name = parts
        .next()
        .ok_or_else(|| format!("BO_ without name: {rest}"))?
        .to_string();
    // dlc is in the sender part: "7 PCM"
    let dlc: u8 = _sender
        .split_whitespace()
        .next()
        .and_then(|s| s.parse().ok())
        .ok_or_else(|| format!("BO_ without dlc: {rest}"))?;
    Ok(Message {
        name,
        id: id & ID_MASK,
        dlc,
        signals: Vec::new(),
    })
}

/// `EngineRPM : 15|16@0+ (0.25,0) [0|16383.75] "RPM" Vector__XXX`
fn parse_sg(rest: &str) -> Result<Signal, String> {
    let (name, rest) = rest
        .split_once(':')
        .ok_or_else(|| format!("SG_ without ':': {rest}"))?;
    let rest = rest.trim();
    let (layout, _tail) = rest
        .split_once(' ')
        .ok_or_else(|| format!("SG_ without layout: {rest}"))?;

    // layout: start|length@order sign, e.g. 15|16@0+
    let (range, order_sign) = layout
        .split_once('@')
        .ok_or_else(|| format!("SG_ layout without '@': {layout}"))?;
    let (start, length) = range
        .split_once('|')
        .ok_or_else(|| format!("SG_ layout without '|': {layout}"))?;
    let start: u32 = start
        .trim()
        .parse()
        .map_err(|_| format!("bad start bit: {layout}"))?;
    let length: u32 = length
        .trim()
        .parse()
        .map_err(|_| format!("bad length: {layout}"))?;
    let order_sign = order_sign.trim();
    if order_sign.len() != 2 {
        return Err(format!("bad @order/sign: {layout}"));
    }
    let little_endian = match order_sign.as_bytes()[0] {
        b'1' => true,
        b'0' => false,
        c => return Err(format!("bad byte order {c} in {layout}")),
    };
    let signed = match order_sign.as_bytes()[1] {
        b'+' => false,
        b'-' => true,
        c => return Err(format!("bad sign {c} in {layout}")),
    };

    // scale/offset: "(0.25,0)"
    let scale_offset = rest
        .split('(')
        .nth(1)
        .and_then(|s| s.split(')').next())
        .ok_or_else(|| format!("SG_ without (scale,offset): {rest}"))?;
    let (scale, offset) = scale_offset
        .split_once(',')
        .ok_or_else(|| format!("SG_ without scale,offset: {rest}"))?;
    let scale: f64 = scale
        .trim()
        .parse()
        .map_err(|_| format!("bad scale: {scale_offset}"))?;
    let offset: f64 = offset
        .trim()
        .parse()
        .map_err(|_| format!("bad offset: {scale_offset}"))?;

    Ok(Signal {
        name: name.trim().to_string(),
        start,
        length,
        little_endian,
        signed,
        scale,
        offset,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    const DBC: &str = r#"VERSION ""

BS_:

BU_: PCM

BO_ 201 EngineStatus: 2 PCM
 SG_ RPM : 7|16@0+ (0.25,0) [0|16383] "rpm" Vector__XXX

BO_ 271360000 DoorStatus: 1 PCM
 SG_ DriverDoorOpen : 0|1@1+ (1,0) [0|1] "" Vector__XXX
"#;

    #[test]
    fn parses_11_and_unflagged_29_bit_ids() {
        let db = load_dbc(DBC).unwrap();
        assert!(db.messages.contains_key(&201));
        assert!(db.messages.contains_key(&271360000));
        assert_eq!(db.messages[&201].name, "EngineStatus");
        assert_eq!(db.messages[&201].dlc, 2);
        assert_eq!(db.messages[&201].signals[0].name, "RPM");
        assert!(!db.messages[&201].signals[0].little_endian);
        assert!(!db.messages[&201].signals[0].signed);
        assert_eq!(db.messages[&201].signals[0].scale, 0.25);
    }

    #[test]
    fn parses_signed_intel_signal() {
        let db = load_dbc(
            "BO_ 485 Steer: 8 PCM\n SG_ SteeringWheelAngle : 7|16@0- (0.0625,0) [-2048|2047.9375] \"deg\" Vector__XXX\n",
        )
        .unwrap();
        let sig = &db.messages[&485].signals[0];
        assert!(sig.signed);
        assert_eq!(sig.length, 16);
        assert_eq!(sig.scale, 0.0625);
    }
}
