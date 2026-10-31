# Hardware preflight — chưa thực hiện trên thiết bị

2026-10-31: **HARDWARE VERIFICATION REQUIRED / NOT RUN**. Checklist dành cho nhiệm
vụ SAT được phê duyệt sau; Phase 4 chỉ chuẩn bị. Không power-cycle/reset/upload/
install/ACK/write/erase/đổi RF hoặc scanner trong nhiệm vụ này.

| Inventory | Giá trị phải lưu trước SAT |
|---|---|
| FX9600 | serial/SKU/antenna count, FW thực (baseline 3.32.37), bootloader/kernel/CPU, Java vendor/version/architecture (baseline 1.8.0_102) |
| SDK | JAR/native path + SHA/version/ABI/EULA, JavaDoc/sample/release notes khớp hardware/FW |
| Storage | filesystem/mount/private persistent root, free space/flash budget, app user/ACL, FileLock/atomic rename/fsync/directory sync evidence |
| User Application | manager version, exact builder/package/layout/script naming/case/modes, foreground/return/stop timeout, AutoStart/upgrade/uninstall persistence |
| DS5502 | model/SKU/serial/firmware, cable, VID/PID/interface/bcdDevice, host mode, raw/LF config evidence, stable node mapping và exclusive owner |
| ESP32/TFT | exact S3 module/flash/PSRAM, board revision, image/partitions SHA, display ILI9488, GPIO theo tft_setup, SPI20MHz, RAM/stack margin |
| RF/tag | antenna ports và cấu hình đã commission, region/power/interlock/shielding, tag SKU/lot/PC/EPC/full serial TID; không thay setting để thử |
| LAN/time | topology/VLAN/ACL/DHCP/status URL, client load/RSSI, external clock resolution/uncertainty, camera/logic analyzer calibration |
| Release/state | trusted archive SHA + payload checksum, source manifest/compiler, previous compatible release SHA; full offline state backup inventory |

Lệnh **read-only dự kiến** trên reader, chỉ khi có quyền SAT riêng. Lệnh không có
trên BusyBox phải ghi UNSUPPORTED, không cài tiện ích hoặc thay setting tự động:

```sh
uname -a
id
java -version
cat /proc/meminfo
cat /proc/mounts
df -k
for utility in sh sha256sum grep readlink stat stty fuser; do
    command -v "$utility" || printf 'UNAVAILABLE: %s\n' "$utility"
done
ls -l /dev/ttyACM* /dev/serial/by-id /sys/class/tty/ttyACM* 2>/dev/null
```

Sau khi đối chiếu node cụ thể, đọc sysfs (không mở CDC, không gửi byte):

```sh
readlink -f /sys/class/tty/ttyACM0/device
readlink -f /dev/serial/by-id/COMMISSIONED_SCANNER_ID
```

Đọc `idVendor`, `idProduct`, `serial`, `bcdDevice`, `manufacturer`, `product` tại
USB parent đã resolve (không giả định parent depth); đối chiếu label/fixture. Không
thấy serial hoặc by-id thì ghi BLOCKED cho identity, không chọn ACM đầu tiên.
Firmware scanner không suy từ bcdDevice; lấy label/config report hoặc vendor
query đã được phê duyệt riêng. Không thử management command để đoán firmware.

Hash các binary/release bằng `sha256sum <known-path>`; liệt kê metadata của canonical
state root, không dump config/env/password/barcode vào evidence công khai. Không
`cat /dev/ttyACM*`, không `killall`, không unlink lock hoặc dùng `stty raw`.
`stty -F <commissioned-node> -a` chỉ khi bench/exclusive ownership cho phép mở tty;
không chạy song song scanner reader. Capture raw LF thuộc SAT scanner riêng.

`--check-startup` **không read-only tuyệt đối**: có thể tạo journal/binding/lock.
Không dùng trong preflight chỉ đọc trên state production. Nó chỉ được chạy trên
temp fixtures trong Phase 4; commissioning thật cần backup và approval riêng.

Điều kiện GO cho SAT read-only: exact vendor contracts, đúng identity/firmware,
đã loại trừ client khác, app state private persistent, backup có inventory/hash,
operator/test fixture/rollback đã được ký. Chưa đủ một điều kiện thì NO-GO cho
deploy/AutoStart; vẫn có thể hoàn thiện tài liệu local. Kết quả build không là GO.
