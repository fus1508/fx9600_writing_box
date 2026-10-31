# Phase 2 — commissioning, vận hành và rollback

**Runbook dự kiến, không được thực hiện trên reader trong nhiệm vụ này.** Chưa có
Zebra installer được xác minh; commissioning phần thiết bị BLOCKED theo
[hardware-blockers.md](hardware-blockers.md). Những lệnh bên dưới mô tả giao diện
đã triển khai để chuẩn bị SAT, không phải phê duyệt deploy.

## Build và kiểm tra local trên Windows

```powershell
$env:PATH='D:\Tool\ideaIU-2025.2.4.win\jbr\bin;' + $env:PATH
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/stage-release.ps1 `
  -OutputDirectory target/phase2-release-rebuild `
  -TarCommand D:/Tool/Git/usr/bin/tar.exe -ShellCommand D:/Tool/Git/bin/sh.exe
```

Dùng output directory mới, script từ chối ghi đè. Build cần javac hỗ trợ
`--release 8`, java, PowerShell/.NET ZIP, GNU tar và POSIX sh để lint. Không cần
Maven/jar.exe. Script chạy đủ regression, tạo class-path JAR, local tar và SHA-256,
kiểm tra mode/content/bytecode; chạy startup checks từ JAR đã đóng gói.
`check-archive.ps1 -Archive <tar> -TarCommand ... -ShellCommand ...` tái kiểm tra.
`target/archive-qa-*` là file tạm được giữ lại để điều tra; không chứa dữ liệu thật.

## Commissioning sau khi có phê duyệt thiết bị riêng

1. Thu thập đủ vendor evidence về 3.32.37 và sample package. Xác minh launcher
   foreground phù hợp manager; nếu manager cần background/ELF stub, phải sửa và
   review/test lại rồi mới build installer bằng công cụ chính thức. Không upload tar.
2. Dừng app cũ, disable AutoStart cũ, loại trừ mọi RFID client khác. Xác nhận không
   worker/session/RF còn chạy; không dùng mất process làm chứng minh session đã hết.
3. Backup nguyên config và state Phase 1 (journal, `.head`, `.head.pending`, `.fault`,
   marker), lưu checksum và incident reference. Không tạo journal trống thay thế.
4. Chọn persistent private state directory duy nhất, ngoài release; xác minh quyền
   user manager và durability. Giữ absolute journal/marker path Phase 1; state root
   phải là parent của chúng. Ví dụ `/apps/writing-box-state` chưa là mount đã xác nhận.
5. Provision config thật mode 0600, state directory 0700; journal/marker absolute
   cùng parent; không copy example thành config hoạt động khi chưa sửa paths. Không
   sửa config lúc app đang chạy. Secret qua môi trường đã provision, không đặt argv/log.
6. Verify archive SHA từ nguồn tin cậy, allowlist/mode/checksum; release directory
   read-only với app, scripts 0755 và LF. Trên target chạy `verify-release.sh`.
7. `Main --check-startup <absolute-config>` kiểm tra offline (có thể tạo journal
   mới lần commissioning đầu). Exit 21/24 phải đối soát; không xóa latch để thử lại.
8. Cài installer đã xác minh bằng Application Manager, bật AutoStart sau review
   và commissioning approval. Hoàn thành SAT, reboot/power cycles và đo health.
   Không coi màu xanh manager hoặc `CHILD_RUNNING` là RFID READY/production PASS.

## Start, stop, restart

Launcher dùng `FX9600_CONFIG` absolute, mặc định
`/apps/writing-box-state/station.properties`; `JAVA_HOME/bin/java` nếu được cung
cấp, nếu không dùng `java` từ PATH. Cùng environment phải dùng cho cả start/stop.

- Start: `start_fx9600-writing-box.sh` exec supervisor foreground; manager invocation
  còn chờ xác minh. Duplicate start trả lỗi owner, không tạo owner thứ hai.
- Stop: `stop_fx9600-writing-box.sh` -> Main `--stop <config>`, cooperative request
  rồi chờ ownership thực sự kết thúc. Không `killall java`, không xóa lock.
- Restart: stop thành công, kiểm tra status/evidence, rồi start. Exit 24 không được
  chuyển thẳng sang start. Trong giai đoạn điều tra disable AutoStart đã được SAT xác nhận.

Stop không phụ thuộc checksum của example/launcher hoặc các giá trị reader/status
trong config; chỉ đọc canonical state root. Nếu config thiếu/hỏng cả root, provision
`FX9600_STATE_DIR=<canonical-root>` cho stop script để dùng `--stop-state <root>`.
Có thể chạy mode này từ một release Phase 2 tin cậy nếu JAR hiện tại hỏng. Không
đổi root để tìm cách vượt gate. Stop-control timeout tối đa 601 giây để bao phủ cấu
hình supported 2 × 300 giây; thường trả ngay khi child/locks đã kết thúc.

| Triệu chứng | Xử lý |
|---|---|
| Exit 20 | Kiểm tra config/path/range/JAVA_HOME/checksums; không dump secret |
| Exit 21 | Journal/recovery gate: giữ evidence, quarantine/đối soát theo Phase 1; không auto ACK |
| Exit 23 | Xem duplicate/orphan/permission/lock/storage; tìm owner, không unlink lock |
| Exit 24, session fence, hung latch | Cách ly box; xác minh RF/session dừng bằng phương pháp SAT, điều tra offline; không tự restart/reset |
| Exit 25, restart.exhausted | Điều tra crash log/status và budget; không tự reset counter khi boot |
| Scanner thiếu | API/CDC báo disconnected; kiểm tra cáp, node/quyền/raw mode; không giả READY |
| Không HTTP | Kiểm tra supervisor.status/exit trước: safety preflight có thể dừng trước bind HTTP |

Xóa/sửa runtime latch hoặc reset budget không có command tự động. Chỉ thực hiện
offline sau khi supervisor/child/SDK/RF đã dừng được chứng minh, backup toàn bộ
evidence và ghi incident/operator authorization. Không xóa journal hoặc Phase 1
sidecar để reset. Có thể giữ station gated đến khi điều tra xong.

## Upgrade và rollback

Stop trước khi thay release. Giữ nguyên state/config ngoài release, xác minh full
backup gồm mọi file Phase 1 và Phase 2; không uninstall rồi dọn state. Stage release
mới vào thư mục version mới và kiểm tra trước activation; không thay binary đang chạy.

Chỉ rollback sang release hiểu `FXJNL01` **và giữ các khóa/fence Phase 2** hoặc quy
trình offline đã review riêng. Checkpoint `71b761a` hiểu journal nhưng chưa hiểu
session fence/supervisor, nên không tự rollback trực tiếp vào checkpoint đó.
Tuyệt đối không dùng binary trước Phase 1. Không phục hồi journal cũ đè lên evidence
mới hoặc đổi state root; unresolved vẫn gated sau rollback. Nếu không có release
tương thích đã được xác nhận, dừng trạm và điều tra, không downgrade để tiếp tục ghi.
