# Repository Guidelines

## Project Structure & Module Organization

This Maven project targets Java 8 on a Zebra FX9600. In `src/main/java/org/fus/`, `Main` starts the station; `domain/` contains workflow and encoding, `api/` the status endpoint, `config/` configuration, and `device/` the scanner, GPI and RFID adapters. Checks live in `src/test/java/org/fus/`; the ESP32 display project is `screen_writing_box/`. The vendor JAR is `library/Symbol.RFID.API3.jar`; runtime defaults are in `station.properties.example`. See `document/project-context.md` for hardware constraints and `document/operations.md` for deployment.

## Build, Test, and Development Commands

- `mvn package`: compile and package the application; Maven must have access to the local SDK JAR. The JAR is a system-scoped dependency and must also be available at runtime.
- `powershell -File scripts/verify.ps1`: compile Java 8-compatible code and run the standalone checks on Windows. `mvn test` alone does not run these main-based checks.
- On the reader, run `java -cp "classes:Symbol.RFID.API3.jar" org.fus.Main station.properties` after configuring CDC and `FX9600_ACCESS_PASSWORD`.

## Coding Style & Testing

Use four-space indentation, Java 8-compatible APIs, `PascalCase` class names, and `camelCase` methods and fields. Preserve the single production entry point `org.fus.Main`. No formatter, lint tool, test framework, or coverage threshold is configured. Extend `WorkflowCheck` for behavior changes and run it explicitly; distinguish simulated checks from FX9600 hardware tests.

## Hardware & Configuration

Keep secrets out of source and logs; the access password defaults to zero and can be supplied via `FX9600_ACCESS_PASSWORD`. Scanner input is USB CDC with LF; GPI1 is active-low. One physical tag must be isolated: inventory by EPC cannot distinguish duplicate EPCs. EPC length changes remain blocked pending MR6P verification. Never report a write as successful without read-back verification. See `document/deployment-and-recovery.md` when changing watchdog, staging, or recovery.

## Changes & Reviews

No Git history is available in this workspace to establish a commit convention. Use a concise, imperative commit subject. Describe behavior changes, tests performed, hardware limitations, and any configuration or deployment changes in pull requests.
