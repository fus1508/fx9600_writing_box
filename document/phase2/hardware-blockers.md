# Phase 2 — hardware/vendor blockers

Ngày 2026-10-31. **Tất cả mục dưới đây BLOCKED / NOT RUN. Không deploy trong nhiệm vụ.**

## Bằng chứng Zebra chính thức đã đối chiếu

- [Embedded Java SDK Windows User Guide, §9.1 tr.76](https://prod-www.zebra.com/content/dam/support-dam/en/documentation/unrestricted/guide/software/Zebra-FXSeries-Embedded-Java-SDK-UserGuide_Windows.pdf):
  search index đọc được bước dùng FXSeries Application Package Builder, tên/version
  và thư mục build gồm start/stop scripts. Fetch toàn PDF trả 502 (prod-www) hoặc
  406 (www); chưa xác nhận toàn bộ mẫu script/layout/revision.
- [Zebra UserApp API](https://techdocs.zebra.com/dcs/rfid/android/2-0-5-238/apis/com/zebra/rfid/api3/userapp):
  đọc được trang chính thức, mô tả install `.deb`, start, stop, autoStart,
  getRunStatus. Đây là API quản lý reader từ Android, không phải chứng cứ chính
  xác về Java embedded package layout hoặc firmware 3.32.37.
- [Reader Interface Control Guide](https://www.zebra.com/content/dam/support-dam/en/documentation/unrestricted/guide/software/interface-control-guide-en.pdf):
  search index liệt kê FX9600 có quản lý UserApp và start/stop/AutoStart. Không
  suy manager tự restart crash, chờ cleanup hoặc nhận supervisor process.

Kết luận: `.deb` và AutoStart có bằng chứng cấp dòng sản phẩm, **chưa xác minh
package/lifecycle chính xác cho 3.32.37**. Không tạo installer giả định. Tar local
là đầu vào tham khảo cho bước builder sau, không upload vào Application Manager.
Không giả định systemd, root, cron hoặc rc.local.

## SAT/vendor evidence bắt buộc

| Hạng mục | Cần chứng minh trước khi deploy/production |
|---|---|
| Package 3.32.37 | Release notes + SDK/sample chính xác, package name/layout/architecture, Java app wrapper được chấp nhận, builder trên máy dev |
| Start/stop contract | Foreground/background, working dir/env/user `rfidadm`, timeout, manager nhận đúng supervisor, stop callback, AutoStart persistence qua upgrade |
| Java và utilities | Java 8 runtime thật, native JAR đúng SDK, `/bin/sh`, sha256sum/grep, đủ RAM cho 2 JVM |
| Storage | Persistent mount, quyền private, FileLock qua process/reboot, force/atomic rename/directory fsync trên flash, brownout tất cả crash boundaries |
| Session cleanup | RF/native thực sự dừng sau disconnect/Dispose/crash/halt/force kill; không SDK session chồng lấn; orphan parent chết |
| Hung/stop | Chặn connect/GPI/access/Dispose/CDC có kiểm soát, đo watchdog/stop; không false READY/PASS/restart unsafe |
| Scanner | Boot thiếu CDC vẫn status up, hotplug/reconnect trên kernel/driver thật; tty raw/LF và quyền đọc |
| Upgrade/rollback | Backup/restore nguyên evidence, không reset binding/count/latch; không app cũ song song; manager behavior qua upgrade/uninstall |
| Cold boot | Ít nhất 20 power cycles sau commissioning, không cần SSH/operator command, một owner, không write tự phát; đo boot-to-status |
| Phase 1 SAT | Toàn bộ [blockers Phase 1](../phase1/hardware-blockers.md), MR6P/full TID/RF enclosure và safety verification vẫn giữ nguyên |

Không có cấu hình trong Phase 2 mở production write. Hardware verification sau
này cần mission/review riêng; local tests không chuyển bất kỳ dòng SAT nào thành PASS.
