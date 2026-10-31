# ESP32-S3 firmware build

2026-10-31: **VERIFIED LOCAL — BUILD PASS** bằng PlatformIO 6.1.18, espressif32
6.10.0, Arduino core package 3.20017.241212+sha.dcc1105b (Arduino 2.0.17), Xtensa
8.4.0+2021r2-patch5, TFT_eSPI2.5.43, ArduinoJson6.21.5. Không đổi pin/library version.
Environment `esp32-s3-build-check` kế thừa `esp32-s3-supermini`, chỉ dùng placeholder
config.example. **Không phải firmware deploy; hardware runtime/SAT NOT RUN**.

| Footprint/kiểm tra | Evidence local |
|---|---|
| Static RAM | 47.616 / 327.680 bytes (14,5%); phần còn lại 280.064 bytes chưa phải runtime free heap |
| Application flash | 1.177.793 / 1.310.720 bytes (89,9%); còn 132.927 bytes trong app slot, hạn chế thêm code/font |
| Boot image | esptool image_info xác nhận S3, DIO, flash4MB; board profile label mặc định N8/8MB không phải bằng chứng board thật |
| Partitions | default.csv: app0/app1 mỗi1280KiB, kết thúc toàn table ở4MB, coredump cuối64KiB; parsed binary PASS |
| Image integrity | esptool checksum + embedded validation hash valid; artifact file SHA riêng dưới đây |
| Warning | TOUCH_CS không định nghĩa: chức năng touch không build; không sửa wiring để loại warning |

Firmware file SHA256:
`c565cd15f821d96035449991a73e77e45053da134c4dd04c343f729350ee13d7`.
ELF `05c5abb1e40d4a3502b4bdf70d7e6f5d7864fb10837168758bd1ce7cd11a085e`.
Bootloader `9bf9aab34441621d794360ca427cbddc3d62f38a7950f8790696bb3d7986769f`;
partitions `148b959cbff1c38aa8e1d5c0ba9d612c54997b945e56a63f41223eef650653a1`.
Output `target/f4/esp32-s3-build-check/{firmware.bin,firmware.elf,bootloader.bin,
partitions.bin,SHA256SUMS}`. Không upload, không scan serial port.

## Test-first và lỗi thực tế

- Lượt đầu tải framework bị IncompleteRead; PlatformIO thử mirror khác và tải đủ.
- Embedded Python isolated `_pth` bỏ script directory khỏi sys.path, làm esptool
  không import được; thêm `sitecustomize.py` **chỉ runtime local** phục hồi script path.
- GCC đơn giản chạy được nhưng PIO expanded include command quá dài trên Windows,
  gây CreateProcess. Chuyển tools được tạo trong target sang `target/p4`, libraries
  `target/l4`, build `target/f4`; không thay system PATH/registry hay toolchain flags.
- Sau sửa môi trường: compile RED thật với WIFI_SSID/WIFI_PASSWORD/STATION_STATUS_URL
  undefined. `config.h` không tồn tại, compiler có thể resolve header cùng tên từ SDK.
  Đổi include thành exact project-relative, thêm sample-only build env để CI local
  không cần secret. Không tạo/ghi đè config.h thật.
- `firmware-green.txt`: compile/link/bin/size SUCCESS. Host C++ parser/reducer dùng
  JSON thực từ Java API cũng PASS; không có lỗi ArduinoJson API trong build pinned.

## Concurrency và ngân sách runtime

**VERIFIED LOCAL từ source:** HTTP task không chạm TFT/controller, loop là một TFT
owner, mailbox length1 dùng overwrite; loop nhận không block và nghỉ20ms. Task network
priority1, stack16.384 **bytes** theo header ESP-IDF bundled, không phải16K words.
JSON arena6144 trên stack, message/snapshot khoảng1KiB, HTTP body≤8192 trên heap;
line sprite rotation landscape 480×55×2 =52.800 bytes heap chưa gồm glyph/driver/WiFi.
Queue/task/sprite cấp phát runtime không xuất hiện đầy đủ trong số static RAM.
Nếu queue/task không tạo được thì không có sample mới, UI không thành READY; sprite
allocation có fallback vẽ trực tiếp. Không có bằng chứng latency/heap margin runtime.

**HARDWARE VERIFICATION REQUIRED:** task high-water/stack overflow, heap fragmentation,
largest block và minimum heap dưới HTTP/Wi-Fi/TFT load; OOM path, task scheduling/
watchdog, glyph clipping/long lines, SPI/TFT visible completion, USB CDC Serial thực,
S3 module flash/PSRAM thật. Timing constants không phải hardware bounds.

## Tái hiện không cài global

Máy mới cần Python thật + PlatformIO6.1.18; môi trường này dùng CPython3.12.10
embeddable từ [python.org](https://www.python.org/ftp/python/3.12.10/), Authenticode
python.exe Valid/Python Software Foundation; ZIP SHA256
`4acbed6dd1c744b0376e3b1cf57ce906f9dc9e95e68824584c8099a63025a3c3`.
Lưu/giải nén dưới `target/phase4-tools/python` khi directory chưa tồn tại. Không
overwrite tool/runtime hiện có. Không dùng WindowsApps python alias.

Đã tải [get-pip.py](https://bootstrap.pypa.io/get-pip.py), SHA256
`f4448a01e3ab2914c65f4ca59d4cacba6b2dbde19a483fbac46587f8bbaff424`.
Đối chiếu digest trước khi thực thi; bản upstream đổi thì điều tra, không bỏ check.
Trong python312._pth bật `import site`; sitecustomize.py local chứa:

```python
import os
import sys
if sys.argv and sys.argv[0].endswith('.py'):
    sys.path.insert(0, os.path.dirname(os.path.abspath(sys.argv[0])))
```

```powershell
target/phase4-tools/python/python.exe target/phase4-tools/get-pip.py --no-warn-script-location
target/phase4-tools/python/python.exe -m pip install -r document/phase4/toolchain-requirements.txt --no-warn-script-location
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-screen.ps1
```

[PlatformIO installation docs](https://docs.platformio.org/en/latest/core/installation/methods/installer-script.html)
hỗ trợ cài không admin và pip cho CI; `_pth` shim là điều chỉnh local của môi trường
embeddable này, không phải hướng dẫn PlatformIO chính thức. Script chỉ gọi `pio run`
build-check, không có target upload; environment của caller được restore trong finally.
Firmware reproducibility giữa hai clean build/toolchain khác **chưa đo**; Java tar
reproducibility là kiểm tra độc lập, không áp claim đó cho firmware.
