# F-UPGRADE Spec

## Goal

Move the project toolchain to Java 21 and Spring Boot 3.x without changing the public JSON contract or business behavior captured by F-SAFETY-NET.

## Acceptance Criteria

- [x] Maven builds and tests run on the default JDK 21 shell without a `JAVA_HOME` override.
- [x] `pom.xml` uses Java 21 and Spring Boot 3.x latest stable selected at implementation time.
- [x] Lombok is compatible with JDK 21 through Spring Boot dependency management.
- [x] No `javax.*` imports remain; use `jakarta.*` where Spring Boot 3 requires it.
- [x] Formatting and style checks are available through Maven and run with `./mvnw verify`.
- [x] `./mvnw verify` passes and still produces the JaCoCo report.
- [x] README / agent docs / spec state no longer tell developers to run under JDK 11.

## Verification

```bash
./mvnw verify
./mvnw test -Pslow
```
