# BLOCKED — phần cứng và firmware

2026-10-31. Không deploy/upload/ghi/erase, không truy cập thiết bị thật.

| Mục | Trạng thái / evidence cần bổ sung |
|---|---|
| ESP32 PlatformIO build | NOT RUN: không có pio/platformio, Python runtime và Xtensa toolchain khả dụng. Host Zig C++11 không thay build espressif32 6.10.0. |
| TFT, scheduling, RAM | BLOCKED: kiểm tra task stack 16KB, mailbox/JSON 6KB, Wi-Fi/HTTP/TFT task scheduling, glyph, dòng dài, render thật trên S3/ILI9488 |
| Java 8 + native SDK | BLOCKED: --release 8 trên JBR21 không thay runtime 1.8.0_102/native JAR/FW 3.32.37 |
| Reader detection | BLOCKED: callback disconnect/exception latency, GPI/connect/isConnected/Dispose hang, RF/antenna khác control-plane; giữ session fence/watchdog |
| DS5502 CDC | BLOCKED: raw/LF, quyền/exclusive owner, symlink theo serial, node renumber, fileKey/character node trên target, EOF/read/close/open hang, TOCTOU path/open, kernel tái dùng node |
| Scanner liveness | BLOCKED: plain CDC chưa có management query được xác minh; không báo responsive READY từ port-open/decode. Không gửi lệnh SSI/SNAPI/RTA thử |
| p95 <=500ms / <=1s | NOT RUN trên thiết bị; benchmark localhost không bao gồm USB/SDK detector, LAN, parse/redraw ESP32 |
| Network loss <=3s | Logic host PASS 2500ms offline timer, firmware scheduling/driver/TFT thực phải SAT; không gọi đây là bound hardware đã đo |
| Safety/PnP | Toàn bộ blocker Phase 1/2 tiếp tục có hiệu lực: TID/RF enclosure/fsync/brownout/AutoStart/package lifecycle/session cleanup |

Nguồn Zebra đã kiểm tra ngày 2026-10-31:

- [RTA Programmer's Guide](https://techdocs.zebra.com/dcs/scanners/rta/dev-guide/):
  requires model + firmware hỗ trợ, host protocol SSI/SNAPI/IBM; bảng supported
  products đang đọc không liệt kê DS5502. Đây không phải bằng chứng plain CDC có ping.
- [DS5502 PRG URL chính thức](https://www.zebra.com/content/dam/support-dam/en/documentation/unrestricted/guide/product/ds5502-prg-en.pdf):
  fetch trả 406; không tuyên bố đã đọc được PDF này. Audit repo đã ghi nhận riêng
  PRG qua mirror liệt kê USB CDC/SSI over CDC/SNAPI. Không suy hỗ trợ ARM Linux/native
  driver của FX9600 từ host mode hoặc SDK Windows.

Hướng nghiên cứu sau: xin PRG/release firmware DS5502 đúng thiết bị, command read-only
có response/correlation/timeout, SDK/Linux ARM ABI phù hợp FX9600; review riêng
transport/parser/provisioning trước khi gửi query. RTA idle alert theo phút không
thay subsecond liveness. Không tự đổi host mode hoặc scanner firmware.
