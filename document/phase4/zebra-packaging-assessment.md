# Zebra package & AutoStart assessment

2026-10-31. Target theo repo: FX9600 FW 3.32.37, Java 1.8.0_102;
**HARDWARE VERIFICATION REQUIRED**, chưa kết nối để xác nhận.

**VERIFIED FROM VENDOR DOCUMENTATION**, giới hạn đúng nguồn:

- [Embedded Java SDK Windows guide](https://prod-www.zebra.com/content/dam/support-dam/en/documentation/unrestricted/guide/software/Zebra-FXSeries-Embedded-Java-SDK-UserGuide_Windows.pdf),
  search index chính thức §8.8 tr.75 và §9.1 tr.76: dùng builder từ Eclipse, thư mục
  build chứa start/stop scripts. Mẫu start chạy Java bằng `-jar` ở background dưới
  `/apps`; stop dựa tên JAR. Tên script theo app, cần Unix line endings.
  Full PDF fetch vẫn 502/406; revision chưa xác minh. Chỉ coi đoạn index là evidence.
- [UserApp API 2.0.5.238](https://techdocs.zebra.com/dcs/rfid/android/2-0-5-238/apis/com/zebra/rfid/api3/userapp)
  mô tả install package `.deb`, start/stop, autoStart và getRunStatus.
  Đây là Android management API, không phải contract embedded 3.32.37.

**INFERENCE:** có cơ sở nghiên cứu luồng builder → .deb → Application Manager,
nhưng không đủ để tự viết Debian control scripts, chọn ARM architecture, directory
layout hay coi systemd là supported. Không chứng minh manager tự restart crash.

**NOT RUN / BLOCKED:** SDK builder/sample và release notes đúng FW 3.32.37, exact
start/stop timeout/working dir/user/env/process tracking, Java/native ABI và license.
Mẫu vendor chạy nền còn launcher của repo giữ supervisor foreground. Không đổi
launcher sang background hoặc kill theo tên chỉ dựa mẫu; có thể mất stop/ownership.
Class-path JAR hiện không có Main-Class, nên không thay `-cp ... org.fus.Main`
bằng `java -jar`. Cần vendor xác nhận wrapper/supervisor và 2 JVM RAM trước installer.

**VERIFIED LOCAL:** tar candidate flat allowlist 10 files; scripts 0755, data 0644,
LF/no BOM, `sh -n`, Java class major52, SHA256SUMS và archive checksum. Release
metadata giữ FXJNL01/FXRUNTIME02, production.write=BLOCKED, firmware.sat=BLOCKED;
thêm source input digest/compiler/fixed epoch, kiểm tra provenance và candidate type.
JAR entries sort/timestamp UTC 2000-01-01; GNU tar cùng epoch, uid/gid số 0.
`source-inputs.sha256` cạnh payload chỉ chứa hash/path các source/test/build inputs,
không đọc config thật; digest của manifest nằm trong release.properties.

Reproducibility test chạy hai build mới và so nguyên tar. Phạm vi bảo đảm: cùng
checkout bytes, compiler, .NET ZIP/GNU tar và tool versions; không cam kết JDK/OS
khác cho cùng bytes. SHA là integrity/provenance, không là chữ ký xác thực publisher.
Không đưa config/journal/marker/fence/budget/secret vào payload, không mutation state.

Launcher fake Java kiểm quoting/spaces/exit/stop-state; Java supervisor checks kiểm
crash/backoff/budget/singleton/orphan/hang/stop. Rollback harness chạy **offline**
preflight của hai JAR trên cùng temp state; không mở reader. Kết quả cuối và hash:
[test-results](test-results.md). Đây không là Application Manager SAT.

Trước deploy: có exact SDK/EULA + builder sample, xác nhận foreground/background,
stop timeout ≥ cleanup budget, persistent private state nằm ngoài release, fsync/
FileLock trên target, loại trừ binary/client khác; review lại wrapper rồi build
installer bằng flow vendor. Không upload tar vào Application Manager.
