# Backend working guide

- Preserve Java 17, Spring Boot, MyBatis, /api, ApiResponse and session-based AccessGuard behavior.
- Keep transaction boundaries, batched order-detail queries and atomic stamp updates.
- Root commands: ./scripts/check.sh and ./scripts/dev.sh. MySQL Compose mounts root data.sql.
- Do not commit .env, credentials, target/ or real database settings. Update docs with changes.
- Branches use PRs and Conventional Commits. CI must pass before squash merging.
- Recommendation features, schema fixes and deployment are follow-up work. Mobile is separately owned.
