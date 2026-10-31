# Phase 4 — test results

2026-10-31, Windows PowerShell/JBR21.0.8, compile Java --release8; GNU tar1.35,
Git sh, Zig0.13.0 host C++11; CPython3.12.10/PlatformIO6.1.18 local trong target.
**VERIFIED LOCAL** dưới đây; không deploy/upload/SDK session/RFID write/erase thật.

| Kiểm tra cuối | Kết quả và phạm vi |
|---|---|
| Phase1/2/3 Java regression | PASS13/13 qua verify trong **cả hai** full build final; crash/partial write/identity/journal/ACK/health/CDC dùng fake RF/child JVM/temp state/localhost |
| Java --release8 + packaged startup | PASS production/tests compile, class major52, offline StartupCheck từ packaged JAR; không target Java8/native |
| Host screen | PASS2/2 parser/reducer C++11 -Wall -Wextra -Werror với JSON Java API thực; firmware scheduling/render thật NOT RUN |
| Java event→HTTP localhost | SIMULATED N300, missed0/timeouts0, p50=600us, p95=1349us, max=2251us; không LAN/TFT pipeline |
| PlatformIO firmware | BUILD PASS esp32-s3-build-check, đúng pinned libs; RAM47616/327680, flash1177793/1310720; image/partition parse PASS, placeholder config |
| Release integrity | PASS checksum, flat allowlist10regular files, LF/no BOM/sh syntax, JAR loading, tar POSIX modes0755/0644, extracted payload |
| Reproducibility | PASS2/2 fresh full builds, tar bytes/hash trùng; same checkout/compiler/.NET/GNU tar, không cross-toolchain guarantee |
| Release faults | PASS12/12 rejected: checksum/CRLF/missing/extra/bytecode/mode/path/case/provenance/installer-claim/near-match key/duplicate key |
| Launcher | PASS fake Java shell exec/quoting/spaces/absolute config/exit propagation/stop-state và stop khi unrelated checksum hỏng |
| Supervisor | PASS trong regression: child crash/budget/backoff/clean/config exits/duplicate/orphan/hang/fence/stop/backoff-stop/stop-timeout; không native cleanup proof |
| Rollback | PASS offline ReleaseRollbackCheck: Phase3→candidate→Phase3, cùng temp unresolved journal + config/binding/budget, session/hung fence bytes không đổi; exit21/24 giữ gate |
| Latency analysis tool | PASS13 unit cases, empty SAT template rejected exit2; chỉ offline data validation/statistics, không hardware acceptance |
| SDK | PASS reflection false/no constructor + IdentityCheck fake policy; native/firmware/TID full uniqueness NOT RUN |
| git diff --check | PASS; staged rỗng, production Java/domain/runtime/deploy/vendor JAR không đổi |

## RED → GREEN evidence

- `reproducibility-red.txt`: hai build cùng source có tar hashes khác nhau. Sau
  sort ZIP/fixed UTC timestamp + tar epoch/numeric owner, `reproducibility-green.txt`
  PASS; sau final source và review fix, `reproducibility-final.txt` PASS lại.
- `provenance-red.txt`: package thiếu source digest được chấp nhận trước validator
  mới; sau check provenance/type thì rejected.
- `latency-tool-red.txt`: test đã có trước tool (FileNotFoundError), rồi
  `latency-tool-green.txt` và `latency-tool-final.txt` PASS13, có wrong clock,
  insufficient samples, lost outcomes, duplicate, NaN/inf, uncertainty và max network.
- `firmware-red.txt` môi trường tải/long path/Python import lỗi; sau sửa tool local,
  `firmware-shortpath-red.txt` compile thật thiếu macros do generic config.h collision.
  Include exact + sample env → `firmware-green.txt` SUCCESS; không đổi GPIO/library.
- Reviewer Minor metadata regex/duplicates: test-first
  `review-metadata-red.txt` chấp nhận near-match key; parse exact case-sensitive
  key/value + reject duplicates → `review-metadata-green.txt` PASS12, rồi
  `release-faults-final.txt` PASS12 trên artifact cuối.

Rollback harness là test bổ sung trên implementation preflight hiện có, không có
production fix hoặc lượt RED được giả ghi cho nó. Host parser/reducer không đổi;
đã chạy regression. Không dùng tests này để suy acceptance phần cứng.

## Lệnh tái hiện

```powershell
$env:PATH='D:\Tool\ideaIU-2025.2.4.win\jbr\bin;' + $env:PATH
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify.ps1
java -cp 'target/classes;target/test-classes' org.fus.HealthLatencyCheck
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-screen.ps1 `
  -Compiler target/phase3-tools/zig-windows-x86_64-0.13.0/zig.exe -Zig `
  -JsonInclude target/phase3-tools/json
target/phase4-tools/python/python.exe scripts/test_sat_latency.py
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-screen.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-reproducibility.ps1 `
  -OutputPrefix target/phase4-rebuild `
  -TarCommand D:/Tool/Git/usr/bin/tar.exe -ShellCommand D:/Tool/Git/bin/sh.exe
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-release.ps1 `
  -Directory target/phase4-rebuild-a/payload `
  -TarCommand D:/Tool/Git/usr/bin/tar.exe -ShellCommand D:/Tool/Git/bin/sh.exe
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-launchers.ps1 `
  -Directory target/phase4-rebuild-a/payload -ShellCommand D:/Tool/Git/bin/sh.exe
java -cp 'target/classes;target/test-classes' org.fus.ReleaseRollbackCheck `
  target/phase4-rebuild-a/payload/fx9600-writing-box.jar `
  target/phase3-release-handoff-final/payload/fx9600-writing-box.jar
git diff --check
```

Dùng output prefix mới; không overwrite artifact. Repro test tự build/verify cả hai
lượt. Rollback cần baseline Phase3 JAR tin cậy đã có; nếu thiếu phải BLOCKED, không
lấy binary khác/đổi state production. Firmware setup xem [build report](firmware-build-report.md).

Raw evidence ignored ở `target/phase4-evidence/` theo tên trên; thêm
`baseline-java.txt`, `host-screen.txt`, `event-http.txt`, `launchers-final.txt`,
`rollback-final.txt`, `empty-template-rejected.txt`, SDK surface và image info.
HealthLatencyCheck vẫn ghi CSV/API fixture vào target/phase3-evidence theo harness
cũ; các source/report Phase3 không sửa. Log phase4 ghi rõ số liệu lượt Phase4.

Artifact cuối Java: `target/phase4-final-a.tar` và twin `phase4-final-b.tar`,
696320 bytes, SHA256 cả hai
`0e946f95881d6f9246776daee5de0617132904c515203c3d662f6ff8262c8ad0`.
Manifest Java release inputs SHA256
`0a1cd3ac1b9f42c79b76a96853c016d8bfa5309456d88635e8015439748d16af`;
file `target/phase4-final-a/source-inputs.sha256`, digest nằm trong release.properties.
Không phải digest toàn repository/firmware; các source/test/build script cho Java
release được liệt kê. Metadata source.baseline=3b6f9ff, uncommitted-phase4.
Artifact red/green trước final chỉ giữ để điều tra, không bản bàn giao.

Java obsolete/deprecated warnings, TFT missing TOUCH_CS và PowerShell
NativeCommandError wrapper với expected stderr không là fail khi exit0 và suite
PASS. Expected rejection của empty CSV exit2/relative config exit20 là assertion
thành công. Không chạy mvn package (Maven không có PATH); mvn test không thay checks.
Target Java8/native, scanner thật/TFT/RF/storage/brownout/AutoStart/SAT: NOT RUN/BLOCKED.
