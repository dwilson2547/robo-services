/**
 * User_Setup.h  —  TFT_eSPI configuration
 * Target  : Seeed Studio XIAO ESP32-S3
 * Display : Hosyond 4.0" ST7796S 480x320 SPI TFT + XPT2046 touch
 *
 * INSTALLATION
 *   Copy this file to:
 *     Arduino/libraries/TFT_eSPI/User_Setup.h
 *   (overwriting the existing placeholder)
 *
 *   OR in User_Setup_Select.h, point to this file with:
 *     #include </path/to/this/file>
 *
 * TOUCH CS NOTE
 *   T_CS is wired to D4 / GPIO5 (not D6/GPIO43) to keep
 *   UART0 TX free for Serial debug output.
 *   Update TOUCH_CS if you moved the wire.
 */

#pragma once

// ---------------------------------------------------------------------------
// Driver selection — comment out all others in the library if present
// ---------------------------------------------------------------------------
#define ILI9488_DRIVER

// ---------------------------------------------------------------------------
// Physical panel dimensions (portrait native — rotation set in sketch)
// ---------------------------------------------------------------------------
#define TFT_WIDTH   320
#define TFT_HEIGHT  480

// ---------------------------------------------------------------------------
// SPI pins — XIAO ESP32-S3 hardware VSPI
// ---------------------------------------------------------------------------
#define TFT_MOSI    9     // D10 / GPIO9
#define TFT_SCLK    7     // D8  / GPIO7
#define TFT_MISO    8     // D9  / GPIO8

// ---------------------------------------------------------------------------
// Display control lines
// ---------------------------------------------------------------------------
#define TFT_CS      4     // D3  / GPIO4  — display chip select
#define TFT_DC      2     // D1  / GPIO2  — data/command
#define TFT_RST     3     // D2  / GPIO3  — reset (active low)
#define TFT_BL      1     // D0  / GPIO1  — backlight PWM (managed in sketch)

// ---------------------------------------------------------------------------
// Touch (XPT2046 resistive, shares SPI bus)
// ---------------------------------------------------------------------------
#define TOUCH_CS    5     // D4  / GPIO5  — touch chip select
//      T_IRQ    GPIO44  D7   — wire but manage in sketch; not defined here

// ---------------------------------------------------------------------------
// SPI clock speeds
// ---------------------------------------------------------------------------
#define SPI_FREQUENCY         27000000   // 27 MHz — safer on breadboard/long-wire runs
#define SPI_READ_FREQUENCY    20000000   // read path is slower
#define SPI_TOUCH_FREQUENCY    2500000   // XPT2046 max ~2.5 MHz

// ---------------------------------------------------------------------------
// Performance
// ---------------------------------------------------------------------------
#define ESP32_DMA                         // enable DMA transfers (S3 supports it)
//#define USE_HSPI_PORT                   // uncomment to use HSPI instead of VSPI

// ---------------------------------------------------------------------------
// Fonts — include what you need; each adds ~5-20 kB to flash
// ---------------------------------------------------------------------------
#define LOAD_GLCD     // Adafruit GLCD 5x7 (always include)
#define LOAD_FONT2    // Small 16px
#define LOAD_FONT4    // Medium 26px
#define LOAD_FONT6    // Large 48px digits (0-9 . : only)
#define LOAD_FONT7    // 7-segment 48px (0-9 . : - only) — good for speed display
#define LOAD_FONT8    // Large 75px (0-9 . : only) — use for main speed number
#define LOAD_GFXFF    // FreeFont support
#define SMOOTH_FONT   // Anti-aliased fonts from SPIFFS/LittleFS
