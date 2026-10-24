# AI Usage Log

This project (backend and frontend repositories) was built with **Claude Code** (Anthropic's CLI coding
agent, model Claude Opus 5.5) working directly in the two project folders on Windows 11. This log is
identical in both repositories.

Development happened in two sessions:

1. **Session 1:** built the prototype from a master prompt (commits up to "Add opt-in H2 web console" in
   the backend and "Clear stale add-participant errors" in the frontend).
2. **Session 2:** UI redesign, new features driven by manual testing, a senior-level code review, and
   these documents (all later commits).

## Prompts used

The prompts below are the instructions given to the AI, in order. Short prompts are quoted as written;
long ones are summarized.

### Session 1: build the prototype

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

### Session 2: improve, extend and review

4. *"i cheked my fornt-end and i think ui part improve and error message shoud be improve and ux is make
   more improvement"*: friendlier error and validation messages, focus on the first invalid field,
   password show/hide, in-app confirm dialog instead of `window.confirm`.
5. *"please make uplode photo like click profile picture that pencile type buttion show and than uplode"*
   (with a screenshot): avatar upload by clicking the photo, with preview, save/cancel and drag-and-drop.
6. *"when user login that time always show dashborad … completed meeting than upcomming meeting … after we
   can show graphical view … if need baacked api you can create"*: dashboard with summary cards, a
   meetings-per-month chart and next/recent lists; login always lands on the dashboard.
7. *"make the layout proper like the admin dashboard … show all the menus in the sidebar"*, followed by
   *"can you use the tailwind css to make the more interative designs"*: collapsible sidebar layout and a
   move from plain CSS to Tailwind CSS 4.
8. *"the add meeting page … show in the full width and also make the date and time picker seprate and make
   this picker more good"*: full-width form with a live summary, calendar date picker, time dropdowns and
   duration chips.
9. *"when my meeting was complete then make user can not change the meeting details … make all the changes
   as in the api side not only for the UI side"*: completed meetings locked in the API (`409`) and the UI.
10. *"i can see why dublication time api call can you fix ??"* (with a Network-tab screenshot): duplicate
    requests from React StrictMode and repeated avatar downloads.
11. *"adding pagination and searchin our meeting list … must be api base keep not ui side"*: server-side
    search, filter tabs and pagination, plus a stats endpoint for the dashboard.
12. *"Senior Developer Code Quality Review — Meeting Planner"*: a long review brief asking for an
    inspect → report findings → refactor → verify → final report workflow across both repositories,
    without unnecessary rewrites or abstractions.
13. Follow-ups: list the test cases, explain the API endpoints per model, *"ok fix frist and reslolve
    error"*, explain the H2 setup, and this request to meet the submission checklist (multiple commits,
    README sections, AI log).

## What the AI did

| Area | AI contribution |
|------|-----------------|
| Inspection | Session 1: checked both folders (both empty, no git), the installed JDKs, Maven, Node and network access. Session 2: read every source file before each change |
| Planning | Package-by-feature layout, endpoint design, authorization model, token/storage strategy; in session 2, API shapes for paging, stats and the completed-meeting rule |
| Backend code | Entities, repositories, services, controllers, DTOs, JWT, security config, exception handling, avatar storage; later the meeting lock, JPA Specifications for search/filter/paging, the stats endpoint, an injectable `Clock`, and review fixes |
| Backend tests | 57 tests in session 1; 73 after session 2 (unit tests plus MockMvc integration tests, including a SQL statement-count test for N+1 queries) |
| Frontend code | Session 1: the full React app. Session 2: Tailwind redesign, dashboard and chart, date/time pickers, paging and search UI, request sharing, review refactors |
| Frontend tests | 26 tests in session 1; 74 after session 2 (Vitest + React Testing Library) |
| Docs and git | Both READMEs, this log, and the commits in each repository |

## Key decisions the AI made (and why)

- **Invitee read-only access.** The organizer has full control; a user whose account e-mail matches a
  participant e-mail can view but not modify (`403`); everyone else gets `404`, so meeting IDs can't be
  probed.
- **Owner always comes from the JWT.** Request bodies have no owner field; a test sends a spoofed
  `createdBy` to show it is ignored.
- **Fail-fast JWT secret.** No default secret is shipped; the app refuses to start without one of at least
  32 bytes.
- **Avatar validation by magic bytes**, server-generated file names, path containment checks and an
  authenticated download endpoint.
- **Completed meetings are locked in the API, not just hidden in the UI.** Changes after the end time
  return `409`. The organizer can still delete them. Time comes from a `Clock` bean so tests are
  date-independent.
- **Search, filters and paging run on the server**, and the dashboard uses a stats endpoint, so the client
  never downloads every meeting. Months are bucketed in the user's time zone.
- **No generic `ApiResponse<T>` wrapper.** The review brief mentioned one, but the API already returns
  typed DTOs and one consistent `ApiError`; a wrapper would have changed every contract for no gain.
- **Tailwind with a few `@apply` primitives** (buttons, cards, badges, fields, chart) and utilities for
  layout, rather than a component library.
- **Dependency versions.** Vite 6 / Vitest 3 / jsdom 26 because the machine has Node 20.10; Tailwind 4.3.3
  pinned to an exact, released-for-months version.

## How the generated code was reviewed, tested and corrected

Problems found during verification and fixed:

1. **Session 1, Spring context failed to start.** Two constructors in `JwtService` and
   `AvatarStorageService`; fixed with `@Autowired` on the injection constructor.
2. **Session 1, new participant returned without an id.** `saveAndFlush(meeting)` merged a copy; fixed
   with `flush()`. Caught by `MeetingIntegrationTest.addAndRemoveParticipant`.
3. **Session 1, line endings.** `.gitattributes` forces LF for `mvnw`.
4. **Session 2, tests that would expire.** Fixtures used fixed November 2026 dates, so the new
   completed-meeting rule would have broken them a week later. Fixed with a fixed test clock (backend) and
   far-future or fake-timer dates (frontend).
5. **Session 2, layout bugs found in screenshots:** a missing space in a class name (unstyled badges) and
   grid overflow at phone width.
6. **Session 2, locale-dependent tests.** Form tests assumed en-US formats while the machine uses en-IN;
   tests now build expected text with the app's own formatters.
7. **Session 2 code review (findings report, then fixes, each with a test):** the participant limit could
   be bypassed through the add endpoint; N+1 queries for participant counts (the new test fails without
   `@BatchSize`: 19 vs 9 statements); a login timing leak for unknown e-mails; avatar files deleted before
   the transaction committed; the search box re-applying an old search after sidebar navigation; the pager
   hiding a single page behind "…"; plus duplicate code removed (shared tab counts, `useMeeting`,
   `LIMITS`, `BackLink`, `MeetingSummary`).
8. **Session 2, broken build hidden by the IDE.** A typo (`conflxict`) introduced during manual editing
   compiled "successfully" because VS Code's Java extension had written error-stub classes into
   `target/classes`. Found by inspecting the bytecode, fixed, then verified with a clean rebuild.

Verification actually performed (end of session 2):

- Backend: **73 tests, 0 failures** after a clean recompile of all classes (`BUILD SUCCESS`).
- Frontend: **74 tests in 14 files, all passing**; `npm run build` succeeds.
- The packaged backend jar was started and checked with `curl` (`401` without a token, `400` with field
  errors for invalid signup).
- Screens were checked in headless Chrome at desktop and phone widths, using a scratch build with mock
  data (dashboard, meetings list, detail, new meeting with open pickers, profile, login).

## Known limitations of the AI-assisted work

- The original assignment text was not available to the AI; React was used because the build brief named
  it.
- No refresh tokens, revocation or login rate limiting (documented as prototype limitations).
- The UI was checked with screenshots of a mock-data build, not by a person clicking through the real app
  end to end.
- Git commits use the machine's existing git identity; the AI did not change it.
