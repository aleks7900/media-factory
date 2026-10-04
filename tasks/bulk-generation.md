# Bulk generation implementation

- [x] Inspect existing providers, storage, queues, domain and frontend.
- [x] Safe bounded ZIP parser and parser tests.
- [x] Durable batch/task import, lifecycle, controls and exports.
- [x] GPT image processor through existing OpenAI integration.
- [x] Gemini/Veo adapter through existing async video provider port.
- [x] Shared restart/retry/idempotency/concurrency controls and costs.
- [x] Two frontend sections, history, task details and automatic progress.
- [x] Integration/provider/frontend tests and 100+ task acceptance.
- [x] Builds, local Compose deployment, health and documentation.

Findings: no existing Gemini adapter. Existing async video providers are mock-video and Runway. Existing OpenAI image provider uses images/generations, advertises one output and no reference-image support. Existing generation_batches is a one-concept diversity pipeline and must not be repurposed to misrepresent independent archive tasks. Reuse provider ports, storage, costs and domain generation resources while adding archive-specific batch/task tracking. Gemini video means Veo through Gemini API; contract source https://ai.google.dev/gemini-api/docs/veo . Never test with paid calls without explicit spending authorization.

Verification (2026-10-04): full backend suite 135 unit tests and 143 integration tests (141 passed; two opt-in scale benchmarks skipped); 61 frontend tests passed. Java JAR, Vite production bundle and Docker images built. V16 deployed with GPU Compose overlay after a 675668-byte database backup. Both bulk pages passed Playwright checks. Live 125-image + 2-video mock acceptance and checksum export verification passed; final evidence is in docs/bulk-generation-verification.md. No paid requests were sent.
