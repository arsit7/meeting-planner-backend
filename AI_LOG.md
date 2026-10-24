# AI Usage Log

This project (backend and frontend repositories) was built with **Claude Code** (Anthropic's CLI coding
agent, model Claude Opus 5.5) working directly in the two project folders on Windows 11. This log is
identical in both repositories.

## Prompts used

The prompts below are the actual instructions given to the AI during development, in order.

1. **Master prompt.** A long specification titled *"Master Prompt: Build Meeting Planner Prototype"*,
   pasted in full. It asked the AI to act as a senior full-stack developer and covered: inspect and plan
   first; stack (Java 17, Spring Boot 3, Spring Security + JWT, BCrypt, H2, Bean Validation, Maven; React +
   Vite + React Router + Axios); auth and security rules; entity design (User, Meeting, Participant);
   meeting business rules; safe avatar upload; REST endpoint design; frontend pages; a mandatory list of
   15 backend test scenarios plus frontend tests; easy local setup with no external services; README and
   AI_LOG requirements; git hygiene; a 2–3 hour timebox; and a final verification report.
2. Accompanying instruction: *"D:\Meeting Planner-back-end :- my backed repo you can create backed releted
   changes this repo … can you strat develemt and make sure my backed changes keep i given repo"*. In
   other words, backend code goes into `D:\Meeting Planner-back-end` and frontend code into
   `D:\Meeting Planner-front-end`.
3. *"continue"* / *"can we continue develoment"*, sent after a network outage interrupted the dependency
   lookup.

## What the AI did

| Area | AI contribution |
|------|-----------------|
| Inspection | Checked both folders (both empty, no git), installed JDKs (8, 17, 19), Maven 3.9.3, Node 20.10, and network access |
| Planning | Chose package-by-feature layout, endpoint design, authorization model, and token/storage strategy |
| Backend code | Wrote all entities, repositories, services, controllers, DTOs, JWT service and filter, security config, exception handling, and avatar storage |
| Backend tests | Wrote 26 Mockito/JUnit unit tests and 31 Spring Boot MockMvc integration tests |
| Frontend code | Wrote the Vite/React app: auth context, protected routes, Axios client, all pages and components, and CSS |
| Frontend tests | Wrote 26 Vitest + React Testing Library tests |
| Tooling | Generated the Maven wrapper, `.gitignore`/`.gitattributes`, and example config files |
| Docs | Wrote both READMEs and this log |
| Git | Initialized both repositories and made commits (see `git log`) |

## Key decisions the AI made (and why)

- **Invitee read-only access.** The brief asked for a documented rule on whether users see only their own
  meetings. The AI chose: organizer has full control; a user whose account e-mail matches a participant
  e-mail can view but not modify (`403`); everyone else gets `404`, so meeting IDs can't be probed.
- **Owner always comes from the JWT.** Request bodies have no owner field, and an integration test sends a
  spoofed `createdBy` to show it is ignored.
- **Fail-fast JWT secret.** No default secret is shipped. The app refuses to start without a secret of at
  least 32 bytes and prints instructions. Developers supply it via the `JWT_SECRET` env var or a
  git-ignored `config/application-local.properties`.
- **Avatar validation by magic bytes** as well as the declared content type, with server-generated file
  names, path containment checks, and an authenticated download endpoint instead of public static files.
- **Participants managed separately from `PUT /meetings/{id}`**, so a stale edit form can't wipe the
  participant list.
- **Vite dev proxy** for `/api`, so the frontend works with no URL or CORS configuration. Backend CORS is
  still restricted to `http://localhost:5173`.
- **Dependency versions.** The first frontend install used older pinned versions, and `npm audit` flagged
  high-severity advisories (axios, react-router). The AI upgraded to current React 19, React Router 7 and
  axios 1.20. Because the machine has Node 20.10, it chose Vite 6 / Vitest 3 / jsdom 26 (the newest majors
  supporting Node 20.10) over Vite 7 / Vitest 5. The remaining advisories are in the dev-only test runner
  and are documented in the frontend README.

## How the generated code was reviewed, tested and corrected

Everything was compiled and run. Problems found during verification and fixed:

1. **Spring context failed to start.** `JwtService` and `AvatarStorageService` each had two constructors
   (one for Spring, one package-private for unit tests), and Spring could not choose. Fix: annotate the
   injection constructor with `@Autowired`. All 31 integration tests had errored. They passed after the fix.
2. **Real bug: new participant returned without an id.** `addParticipant` called `saveAndFlush(meeting)`.
   On an already-managed entity this cascades `merge`, which persists a *copy* of the new participant and
   leaves the returned instance with `id = null`. The test
   `MeetingIntegrationTest.addAndRemoveParticipant` caught it: removing participant id 0 returned `404`.
   Fix: call `flush()`, so the original instance is persisted and receives its id.
3. **Line endings.** Git on Windows would have converted `mvnw` to CRLF, which breaks it on macOS/Linux.
   Added `.gitattributes` forcing LF (and CRLF for `.cmd`).
4. **Frontend refactor.** Duplicate JWT `exp` decoding in `AuthContext` was consolidated into
   `tokenStorage.getTokenExpiryMs`. `crypto.randomUUID` (only available in secure contexts) was replaced
   with a counter for form-row keys.

Verification actually performed:

- `./mvnw test`: **57 tests, 0 failures, 0 errors** (Java 17).
- `npx vitest run`: **26 tests, 5 files, all passed**, with no React `act()` warnings.
- `npm run build`: succeeded.
- Packaged backend jar started **without** a secret: it exited with "JWT signing secret is not configured".
- Packaged backend jar started **with** a secret, then exercised with `curl`: signup → create meeting with
  participant → list → unauthenticated `401`.
- Backend and Vite dev server run together, with calls through the proxy: signup, avatar upload (valid
  PNG → 200, then authenticated download returned `image/png`), and HTML disguised as PNG → `415`.
- Not performed: manual clicking through the UI in a real browser. The UI is covered by component tests
  and the API flows above, but no human visually checked the layout.

## Known limitations of the AI-assisted work

- The original assignment text was not available to the AI. React was used because the build brief named
  it. If the assignment mandates Angular, the frontend needs to be redone (the backend is unaffected).
- No refresh tokens, revocation or rate limiting (documented as prototype limitations).
- The UI was not visually reviewed in a browser by the AI.
- Git commits were authored with the machine's existing global git identity; the AI did not change it.
