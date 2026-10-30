#pragma once

// Match the Clock project's ESP32-S3 SPI peripheral and GPIO mapping.
#define USE_HSPI_PORT
#define TFT_MOSI 11
#define TFT_MISO -1
#define TFT_SCLK 12
#define TFT_CS 10
#define TFT_DC 9
#define TFT_RST 8
#define SPI_FREQUENCY 20000000
#define SPI_READ_FREQUENCY 10000000
#define LOAD_GLCD
#define LOAD_FONT2
#define SMOOTH_FONT
#define SUPPORT_TRANSACTIONS
