from app.services.roles import dbc_signals, validate_roles

DBC = """BO_ 1001 ECMVehicleSpeed: 8 ECM
 SG_ VehicleSpeed : 7|15@0+ (0.01,0) [0|0] "mph" Vector__XXX
 SG_ VehicleSpeedLeft : 39|15@0+ (0.01,0) [0|0] "mph" Vector__XXX

BO_ 1217 OilTemp: 8 ECM
 SG_ EngineOilTemp : 23|8@0+ (1,-40) [0|0] "degF" Vector__XXX

BO_ 497 ECMPRDNL: 8 ECM
 SG_ PRNDL : 3|4@0+ (1,0) [0|0] "" Vector__XXX
"""


def test_signals_are_read_from_the_dbc():
    assert dbc_signals(DBC) == {"VehicleSpeed", "VehicleSpeedLeft", "EngineOilTemp", "PRNDL"}


def test_valid_roles_have_no_problems():
    roles = {
        "speed_kph": {"signal": "VehicleSpeed", "unit": "mph"},
        "oil_temp_c": {"signal": "EngineOilTemp", "unit": "degF"},
        "rpm": {"signal": "VehicleSpeedLeft", "scale": 2, "offset": -1},
        "cylinders": {"value": 6},
    }
    assert validate_roles(roles, DBC) == []


def test_each_mistake_is_reported():
    problems = validate_roles({
        "top_speed": {"signal": "VehicleSpeed"},
        "rpm": {"signal": "EngineRPM"},
        "gear": {"signal": "VehicleSpeed", "value": 3},
        "coolant_c": {"signal": "EngineOilTemp", "unit": "mph"},
        "throttle_pct": {"signal": "VehicleSpeed", "unit": "degF", "scale": 2},
        "cylinders": {"value": True},
        "brake_kpa": "BrakePressure",
    }, DBC)
    text = "\n".join(problems)
    assert "top_speed: not a known role" in text
    assert "rpm: signal 'EngineRPM' is not in this DBC" in text
    assert "gear: give exactly one of signal or value" in text
    assert "coolant_c: a mph signal does not convert" in text
    assert "throttle_pct: unit or scale/offset" in text or "throttle_pct: a degF signal does not convert" in text
    assert "cylinders: value must be a number" in text
    assert "brake_kpa: must be an object" in text


def test_prndl_maps_codes_to_selector_letters():
    assert validate_roles({"prndl": {"signal": "PRNDL", "map": {"1": "P", "2": "R", "4": "D"}}}, DBC) == []
    text = "\n".join(validate_roles({
        "prndl": {"signal": "PRNDL", "map": {"1": "Park"}},
        "gear": {"signal": "PRNDL", "map": {"1": "P"}},
    }, DBC))
    assert "prndl: map keys are integer codes" in text and "gear: only prndl takes a map" in text
    assert "needs a map" in "\n".join(validate_roles({"prndl": {"signal": "PRNDL"}}, DBC))
