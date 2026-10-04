# TASK-12 verification

Run from repository root:

```powershell
node --test scripts/test-skills.mjs
powershell -File scripts/test-skills-backend.ps1 -All
npm test --prefix frontend
npm run build --prefix frontend
powershell -File scripts/install-media-factory-skills.ps1
node scripts/verify-skill-discovery.mjs
```

The backend runner isolates Gradle output from OneDrive locks and copies XML results into backend/build/task12-*-results. Testcontainers requires Docker. Local runtime permissions may be needed for Java caches and Vitest temporary-file renames.

Verified on 2026-10-04:

- Five canonical SKILL.md files passed the official quick_validate.py validator.
- Codex app-server skills/list returned all five repository skills enabled. They also appeared in this desktop conversation's available-skills catalog.
- A fresh read-only Codex execution selected all five intended skills for realistic requests and read the installed instructions. Evidence: backend/build/task12-natural-invocation.txt. No application mutations occurred in that selection test.
- Five client tests passed, including checksum rejection and refusal to overwrite local exports.
- Full backend run: 125 unit tests passed; 129 integration tests passed and two skipped. Nine focused integration tests subsequently passed after live-discovered fixes.
- Frontend: all 56 tests passed; production build passed.
- Backend JAR and Compose images built. V15 deployed after PostgreSQL backup. Backend health UP; frontend /health ok; catalog returned five skills.

## Live acceptance results

- Synthetic research -> collection -> wallpaper: COMPLETED. Research execution e86dd05c-7258-42b8-bdbe-f331fde03af6 preserved provenance and generated no media. Collection execution 134530e9-e37d-4765-bb67-e4dbf3dd066b preserved trend lineage. Wallpaper execution 63ad3d13-e644-47b5-829d-72fa82c7b35d produced six variants and a checksum-verified ZIP. Production e9d188ca-8e25-410a-b19f-9596f75da04c remains PUBLICATION_REVIEW, not published.
- Approved asset -> stock package: COMPLETED. Execution 86eede03-0a6c-4edc-a7ee-4819144c0c2e reused asset 2b54e5b5-9411-4c4d-89a1-5168a2bf8d78. Output JPEG 2048x2048 (4.19 MP), technical valid, metadata reviewed by the isolated mock harness. ZIP includes images, metadata.csv, manifest.json and validation-report.json. ZIP integrity and SHA-256 verified.
- Generated asset -> QA: COMPLETED on the wallpaper fixture with the existing review ID reused and no new generation. Replaying the completed wallpaper execution retained its resources.
- Repetition protection: the generic mock placeholder fixture was DUPLICATE_REJECTED and was preserved. No similarity threshold or duplicate decision was overridden. The successful fixtures used the provider's existing varied, text-free mock mode.
- Staged high-repetition prevention, partial recovery, interrupted reservation handling, missing wallpaper prompt inputs and paid-QA rejection passed integration tests. High repetition rolls back the current dispatch transaction and pauses admission.

Evidence files: storage/data/skills-verification/c4c268da-6403-4a8e-a72f-bb9173b0afad.json and stock-b622b4f3-de70-486c-8907-5129d25dcb73.json; compact domain results in backend/build/task12-live-results.json. These are synthetic acceptance fixtures, not a claim that current market trends were researched. No paid AI generation or external publication occurred.

The first live fixture exposed missing theme/style preflight validation and misclassification of a STAGE_FAILED pause as approval. Both were fixed and regression-tested. Docker Desktop stopped between turns; recovery reused retained IDs. The final deployment health check is recorded in the delivery report.

## Reproduce

`node scripts/skills-smoke.mjs` creates an isolated mock research/collection/wallpaper fixture and preserves any human gate. `node scripts/skills-resume-smoke.mjs <report.json> --approve-own-mock-fixture` permits test-only QA approval for that exact report's mock asset, then verifies resume, export and QA reuse. It never overrides rejected assets or publishes. `node scripts/skills-stock-smoke.mjs` creates a separate free mock source and explicitly approves only its own test metadata. Download helpers reject checksum mismatch and existing local filenames; use a fresh output name for a repeated download.

AMOLED, smart crop, processing and domain publication controls retain their existing backend tests. TASK-12's new live wallpaper smoke used ANDROID_STANDARD; it did not repeat a live AMOLED run. Real paid routes, project ACLs and the future Android API are not validated by these mock tests.
