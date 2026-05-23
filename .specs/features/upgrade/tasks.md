# F-UPGRADE Tasks

- [x] Confirm latest Spring Boot 3.x line available from Maven Central.
- [x] Upgrade Maven parent to Spring Boot 3.5.14 and Java to 21.
- [x] Rely on Spring Boot 3 dependency management for a JDK-21-compatible Lombok.
- [x] Check for `javax.*` imports and migrate if needed.
- [x] Add Spotless using google-java-format.
- [x] Add Checkstyle policy for imports.
- [x] Run formatter and update any style violations.
- [x] Verify default test/build commands on JDK 21.
- [x] Update README, CLAUDE.md, and spec state docs.
- [x] Mark F-UPGRADE complete in roadmap/state after verification.
