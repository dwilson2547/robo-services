#!/usr/bin/env python3
"""Configure NEO-M9N via USB (native CDC-ACM) for the race logger.

Sets 10 Hz measurement rate, disables GLL/GSA/GSV/VTG, enables GGA+RMC
on UART1 (ESP32) and USB (monitoring), then saves to BBR+Flash.
"""

import serial
import time

PORT = "/dev/ttyACM0"

# ─── UBX helpers ─────────────────────────────────────────────────────────────

def ubx_cksum(data: bytes) -> tuple[int, int]:
    a = b = 0
    for byte in data:
        a = (a + byte) & 0xFF
        b = (b + a) & 0xFF
    return a, b

def ubx_packet(cls: int, msg_id: int, payload: bytes) -> bytes:
    hdr = bytes([cls, msg_id, len(payload) & 0xFF, len(payload) >> 8]) + payload
    a, b = ubx_cksum(hdr)
    return b"\xb5\x62" + hdr + bytes([a, b])

# ─── Command builders ─────────────────────────────────────────────────────────

def cfg_rate_10hz() -> bytes:
    # measRate=100ms, navRate=1, timeRef=1 (GPS time)
    return ubx_packet(0x06, 0x08, bytes([0x64, 0x00, 0x01, 0x00, 0x01, 0x00]))

def cfg_msg(nmea_id: int, rates: tuple) -> bytes:
    # rates order: (DDC/I2C, UART1, UART2, USB, SPI, reserved)
    return ubx_packet(0x06, 0x01, bytes([0xF0, nmea_id] + list(rates)))

def cfg_save() -> bytes:
    # CFG-CFG: save all to BBR + Flash + EEPROM
    return ubx_packet(0x06, 0x09, bytes([
        0x00, 0x00, 0x00, 0x00,  # clearMask  (nothing to clear)
        0xFF, 0xFF, 0x00, 0x00,  # saveMask   (everything)
        0x00, 0x00, 0x00, 0x00,  # loadMask
        0x17,                    # deviceMask: BBR | Flash | EEPROM
    ]))

NMEA_GGA, NMEA_GLL = 0x00, 0x01
NMEA_GSA, NMEA_GSV = 0x02, 0x03
NMEA_RMC, NMEA_VTG = 0x04, 0x05

OFF          = (0, 0, 0, 0, 0, 0)
UART1_USB_ON = (0, 1, 0, 1, 0, 0)  # enable on UART1 (ESP32) + USB (laptop)

STEPS = [
    ("Set 10 Hz measurement rate",    cfg_rate_10hz()),
    ("Disable GLL (all ports)",        cfg_msg(NMEA_GLL, OFF)),
    ("Disable GSA (all ports)",        cfg_msg(NMEA_GSA, OFF)),
    ("Disable GSV (all ports)",        cfg_msg(NMEA_GSV, OFF)),
    ("Disable VTG (all ports)",        cfg_msg(NMEA_VTG, OFF)),
    ("Enable  GGA on UART1 + USB",    cfg_msg(NMEA_GGA, UART1_USB_ON)),
    ("Enable  RMC on UART1 + USB",    cfg_msg(NMEA_RMC, UART1_USB_ON)),
    ("Save to BBR + Flash",            cfg_save()),
]

# ─── Main ─────────────────────────────────────────────────────────────────────

def parse_ack(data: bytes) -> str:
    if b"\xb5\x62\x05\x01" in data:
        return "ACK"
    if b"\xb5\x62\x05\x00" in data:
        return "NACK"
    return f"raw={data.hex()}" if data else "no response"

def main() -> None:
    print(f"Opening {PORT} ...")
    with serial.Serial(PORT, 9600, timeout=1) as ser:
        time.sleep(0.3)
        ser.reset_input_buffer()

        for label, pkt in STEPS:
            ser.write(pkt)
            time.sleep(0.15)
            resp = ser.read(ser.in_waiting or 1)
            status = parse_ack(resp)
            print(f"  [{status:4s}] {label}")

        print("\nVerifying — reading NMEA for 3 s ...")
        ser.reset_input_buffer()
        deadline = time.time() + 3.0
        seen: set[str] = set()
        while time.time() < deadline:
            try:
                line = ser.readline().decode("ascii", errors="replace").strip()
            except Exception:
                continue
            if line.startswith("$G"):
                tag = line.split(",")[0]
                if tag not in seen:
                    seen.add(tag)
                    print(f"  {line[:80]}")

        if not seen:
            print("  (no NMEA output — normal without sky view)")
        else:
            allowed = {s for s in seen if s.endswith(("GGA", "RMC"))}
            extra   = seen - allowed
            print(f"\nSentence types seen : {', '.join(sorted(seen))}")
            if extra:
                print(f"WARNING: unexpected types still present: {extra}")
            else:
                print("OK: only GGA / RMC sentences present")

if __name__ == "__main__":
    main()
