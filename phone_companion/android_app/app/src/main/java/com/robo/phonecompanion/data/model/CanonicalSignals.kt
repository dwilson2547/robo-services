package com.robo.phonecompanion.data.model

data class CanonicalSignal(
    val name: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val factor: Double = 1.0,
    val offset: Double = 0.0,
    val description: String = "",
)

object CanonicalSignals {
    val ALL: List<CanonicalSignal> = listOf(
        // Powertrain
        CanonicalSignal("ENGINE_RPM",            "rpm",   0.0,    8000.0,  0.25,      0.0,   "Engine speed"),
        CanonicalSignal("VEHICLE_SPEED",         "km/h",  0.0,    255.0,   1.0,       0.0,   "Vehicle speed"),
        CanonicalSignal("THROTTLE_POS",          "%",     0.0,    100.0,   0.3922,    0.0,   "Throttle position sensor"),
        CanonicalSignal("ACCEL_PEDAL_POS",       "%",     0.0,    100.0,   0.3922,    0.0,   "Accelerator pedal position"),
        CanonicalSignal("ENGINE_TORQUE",         "Nm",    -3276.8, 3276.7, 0.1,       0.0,   "Engine torque"),
        CanonicalSignal("ENGINE_LOAD",           "%",     0.0,    100.0,   0.3922,    0.0,   "Calculated engine load"),
        CanonicalSignal("GEAR_ENGAGED",          "",      0.0,    10.0,    1.0,       0.0,   "Transmission gear currently engaged"),
        CanonicalSignal("GEAR_SELECTOR",         "",      0.0,    7.0,     1.0,       0.0,   "Gear selector position (P/R/N/D/…)"),
        // Fuel & emissions
        CanonicalSignal("FUEL_LEVEL",            "%",     0.0,    100.0,   0.3922,    0.0,   "Fuel tank level"),
        CanonicalSignal("STFT_BANK1",            "%",     -100.0, 99.2,    0.78125,   -100.0, "Short-term fuel trim, bank 1"),
        CanonicalSignal("LTFT_BANK1",            "%",     -100.0, 99.2,    0.78125,   -100.0, "Long-term fuel trim, bank 1"),
        CanonicalSignal("STFT_BANK2",            "%",     -100.0, 99.2,    0.78125,   -100.0, "Short-term fuel trim, bank 2"),
        CanonicalSignal("LTFT_BANK2",            "%",     -100.0, 99.2,    0.78125,   -100.0, "Long-term fuel trim, bank 2"),
        CanonicalSignal("O2_VOLTAGE_B1S1",       "V",     0.0,    1.275,   0.005,     0.0,   "O2 sensor bank 1, upstream (pre-cat)"),
        CanonicalSignal("O2_VOLTAGE_B1S2",       "V",     0.0,    1.275,   0.005,     0.0,   "O2 sensor bank 1, downstream (post-cat)"),
        CanonicalSignal("O2_VOLTAGE_B2S1",       "V",     0.0,    1.275,   0.005,     0.0,   "O2 sensor bank 2, upstream"),
        CanonicalSignal("O2_VOLTAGE_B2S2",       "V",     0.0,    1.275,   0.005,     0.0,   "O2 sensor bank 2, downstream"),
        // Thermal
        CanonicalSignal("COOLANT_TEMP",          "°C",    -40.0,  215.0,   1.0,       -40.0, "Engine coolant temperature"),
        CanonicalSignal("INTAKE_AIR_TEMP",       "°C",    -40.0,  215.0,   1.0,       -40.0, "Intake air temperature"),
        CanonicalSignal("AMBIENT_AIR_TEMP",      "°C",    -40.0,  215.0,   1.0,       -40.0, "Ambient (outside) air temperature"),
        CanonicalSignal("OIL_TEMP",              "°C",    -40.0,  215.0,   1.0,       -40.0, "Engine oil temperature"),
        CanonicalSignal("TRANS_FLUID_TEMP",      "°C",    -40.0,  215.0,   1.0,       -40.0, "Transmission fluid temperature"),
        // Air / intake
        CanonicalSignal("MAP_SENSOR",            "kPa",   0.0,    255.0,   1.0,       0.0,   "Manifold absolute pressure"),
        CanonicalSignal("MAF_AIRFLOW",           "g/s",   0.0,    655.35,  0.01,      0.0,   "Mass air flow rate"),
        CanonicalSignal("BOOST_PRESSURE",        "kPa",   0.0,    511.0,   2.0,       0.0,   "Turbocharger boost pressure"),
        // Dynamics / chassis
        CanonicalSignal("STEERING_ANGLE",        "deg",   -1800.0, 1800.0, 0.1,       0.0,   "Steering wheel angle (+ = right)"),
        CanonicalSignal("YAW_RATE",              "deg/s", -163.84, 163.83, 0.01,      0.0,   "Vehicle yaw rate"),
        CanonicalSignal("LATERAL_ACCEL",         "m/s²",  -16.384, 16.383, 0.001,     0.0,   "Lateral (side-to-side) acceleration"),
        CanonicalSignal("LONGITUDINAL_ACCEL",    "m/s²",  -16.384, 16.383, 0.001,     0.0,   "Longitudinal (fore-aft) acceleration"),
        CanonicalSignal("WHEEL_SPEED_FL",        "km/h",  0.0,    327.67,  0.01,      0.0,   "Front-left wheel speed"),
        CanonicalSignal("WHEEL_SPEED_FR",        "km/h",  0.0,    327.67,  0.01,      0.0,   "Front-right wheel speed"),
        CanonicalSignal("WHEEL_SPEED_RL",        "km/h",  0.0,    327.67,  0.01,      0.0,   "Rear-left wheel speed"),
        CanonicalSignal("WHEEL_SPEED_RR",        "km/h",  0.0,    327.67,  0.01,      0.0,   "Rear-right wheel speed"),
        // Brakes
        CanonicalSignal("BRAKE_PRESSURE",        "bar",   0.0,    204.6,   0.8,       0.0,   "Brake hydraulic pressure"),
        CanonicalSignal("BRAKE_SWITCH",          "",      0.0,    1.0,     1.0,       0.0,   "Brake pedal switch (0=off, 1=on)"),
        // Electrical
        CanonicalSignal("BATTERY_VOLTAGE",       "V",     0.0,    65.535,  0.001,     0.0,   "12 V battery/supply voltage"),
        CanonicalSignal("OIL_PRESSURE",          "kPa",   0.0,    765.0,   3.0,       0.0,   "Engine oil pressure"),
        // Body / BCM
        CanonicalSignal("IGNITION_STATUS",       "",      0.0,    3.0,     1.0,       0.0,   "Key position (0=off, 1=acc, 2=run, 3=start)"),
        CanonicalSignal("TURN_SIGNAL_LEFT",      "",      0.0,    1.0,     1.0,       0.0,   "Left turn signal active"),
        CanonicalSignal("TURN_SIGNAL_RIGHT",     "",      0.0,    1.0,     1.0,       0.0,   "Right turn signal active"),
        CanonicalSignal("HEADLIGHTS_ON",         "",      0.0,    1.0,     1.0,       0.0,   "Headlights active"),
        CanonicalSignal("DOOR_FL_OPEN",          "",      0.0,    1.0,     1.0,       0.0,   "Front-left door open"),
        CanonicalSignal("DOOR_FR_OPEN",          "",      0.0,    1.0,     1.0,       0.0,   "Front-right door open"),
        CanonicalSignal("DOOR_RL_OPEN",          "",      0.0,    1.0,     1.0,       0.0,   "Rear-left door open"),
        CanonicalSignal("DOOR_RR_OPEN",          "",      0.0,    1.0,     1.0,       0.0,   "Rear-right door open"),
        CanonicalSignal("SEATBELT_DRIVER",       "",      0.0,    1.0,     1.0,       0.0,   "Driver seatbelt fastened"),
    )
}
