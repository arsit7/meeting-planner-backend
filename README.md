# Meeting Planner — Backend (Spring Boot)

REST API for the Meeting Planner prototype: sign up / log in, upload a profile picture, create meetings,
and manage meeting participants. It runs entirely locally using an embedded H2 database and local file
storage, with no external services.

The React frontend lives in the companion repository **`Meeting Planner-front-end`** (see its README).

---

## Features

- Signup and login with BCrypt-hashed passwords and signed JWT (HS256) bearer tokens
- Profile endpoint and avatar upload (PNG/JPEG/GIF/WebP, max 2 MB, checked by content signature)
- Meetings: create (optionally with participants in the same request), list, view, update, delete
- Server-side search, filter tabs and pagination for the meeting list, plus a stats endpoint for the
  dashboard, so the client never downloads every meeting
- Participants: add and remove; unique per meeting (case-insensitive e-mail); at most 100 per meeting,
  enforced both on create and when adding one at a time
- Authorization: only the organizer can modify a meeting; invited users (participant e-mail matches their
  account e-mail) get read-only access; everyone else gets `404`
- Completed meetings are locked: once a meeting's end time has passed, its details and participants can't
  be changed (`409`). Responses carry a `completed` flag
- Consistent JSON error format with field-level validation messages
- 73 automated tests (unit + integration)

## Technology stack

| Concern        | Choice                                                     |
|----------------|------------------------------------------------------------|
| Language       | Java 17                                                    |
| Framework      | Spring Boot 3.3.5 (Web, Data JPA, Security, Validation)    |
| Auth           | Spring Security, JJWT 0.12.6, BCrypt                       |
| Database       | H2 (file-based for dev, in-memory for tests)               |
| Build          | Maven (wrapper included: `mvnw` / `mvnw.cmd`)              |
| Tests          | JUnit 5, Mockito, AssertJ, Spring Boot Test, MockMvc       |

## Architecture and folder structure

Package-by-feature. Controllers are thin, business rules and authorization live in services, and entities
never leave the service layer (DTOs only).

```
src/main/java/com/meetingplanner
├── MeetingPlannerApplication.java
├── auth/           AuthController, AuthService, dto/ (SignupRequest, LoginRequest, AuthResponse)
├── user/           User entity, UserRepository, UserService, UserController, dto/UserResponse
├── meeting/        Meeting + Participant entities, MeetingRepository, MeetingService, MeetingController,
│                   dto/ (Create/UpdateMeetingRequest, ParticipantRequest, responses, @ValidTimeRange)
├── storage/        AvatarStorageService (local disk), ImageType (magic-byte detection)
├── security/       JwtService, JwtAuthenticationFilter, AuthUser principal, @CurrentUser
├── config/         SecurityConfig (filter chain, CORS, headers), AppProperties (typed app.* settings)
└── common/         ApiException, ApiError, GlobalExceptionHandler, Emails
src/main/resources/application.properties     dev configuration
src/test/resources/application.properties     isolated test configuration (in-memory H2)
config/application-local.properties.example   template for your local JWT secret
```

## Entity relationships

```
User 1 ──── * Meeting 1 ──── * Participant
(created_by FK)        (meeting_id FK, cascade + orphan removal)
```

| Entity        | Fields                                                                                  | Constraints                                  |
|---------------|-----------------------------------------------------------------------------------------|----------------------------------------------|
| `User`        | id, name, email, passwordHash, profileImagePath, createdAt, updatedAt                    | `email` unique, stored lower-case            |
| `Meeting`     | id, title, description, startTime, endTime, createdBy → User, createdAt, updatedAt       | title/start/end not null; end > start (validated) |
| `Participant` | id, meeting → Meeting, name, email, createdAt                                            | unique (`meeting_id`, `email`)               |

- `Meeting → createdBy` is a unidirectional `@ManyToOne`. `User` has no collection of meetings, which
  avoids JSON recursion and accidental eager loading.
- `Meeting ↔ Participant` is bidirectional because a meeting owns its participants. They are saved and
  deleted together through cascade, and API responses are built from DTOs, so serialization stays safe.
- Timestamps are stored as UTC `Instant`s and exchanged as ISO-8601 strings, for example `2026-11-01T09:00:00Z`.

## API endpoints

All bodies are JSON except the avatar upload (`multipart/form-data`, field name `file`).

| Method | Path                                              | Auth | Description                                   | Success |
|--------|---------------------------------------------------|------|-----------------------------------------------|---------|
| POST   | `/api/auth/signup`                                | No   | Create account, returns token + user          | 201     |
| POST   | `/api/auth/login`                                 | No   | Log in, returns token + user                  | 200     |
| GET    | `/api/users/me`                                   | Yes  | Current user's profile                        | 200     |
| POST   | `/api/users/me/avatar`                            | Yes  | Upload/replace profile image                  | 200     |
| GET    | `/api/users/me/avatar`                            | Yes  | Download current profile image                | 200     |
| POST   | `/api/meetings`                                   | Yes  | Create meeting (optional `participants` list) | 201     |
| GET    | `/api/meetings`                                   | Yes  | One page of meetings you organize or are invited to (search, filter, paging below) | 200 |
| GET    | `/api/meetings/stats`                             | Yes  | Dashboard totals and meetings per month       | 200     |
| GET    | `/api/meetings/{id}`                              | Yes  | Meeting details (organizer or invitee)        | 200     |
| PUT    | `/api/meetings/{id}`                              | Yes  | Update title/description/times (organizer)    | 200     |
| DELETE | `/api/meetings/{id}`                              | Yes  | Delete meeting (organizer)                    | 204     |
| POST   | `/api/meetings/{id}/participants`                 | Yes  | Add participant (organizer)                   | 201     |
| DELETE | `/api/meetings/{id}/participants/{participantId}` | Yes  | Remove participant (organizer)                | 204     |

**Example: create a meeting**

```http
POST /api/meetings
Authorization: Bearer <token>
Content-Type: application/json

{
  "title": "Sprint planning",
  "description": "Agenda in the doc",
  "startTime": "2026-11-01T09:00:00Z",
  "endTime": "2026-11-01T10:00:00Z",
  "participants": [{ "name": "Bob", "email": "bob@example.com" }]
}
```

**Listing, searching and paging meetings**

`GET /api/meetings?q=sprint&filter=upcoming&page=0&size=10`

| Parameter | Default | Meaning                                                                                   |
|-----------|---------|-------------------------------------------------------------------------------------------|
| `q`       | (empty) | Case-insensitive text matched against the title or the organizer's name (max 100 chars)   |
| `filter`  | `all`   | `all`, `upcoming` (not ended), `completed` (ended), `organized` (yours), `invited`        |
| `page`    | `0`     | Zero-based page number                                                                     |
| `size`    | `10`    | Page size, 1–50                                                                            |

`upcoming` is ordered soonest first, `completed` most recent first, and the other tabs show upcoming
meetings before completed ones. Invalid values return `400`. The response:

```json
{
  "content": [{ "id": 7, "title": "Sprint planning", "startTime": "…", "endTime": "…",
                "organizerName": "Olivia", "owner": true, "completed": false, "participantCount": 3 }],
  "page": 0, "size": 10, "totalElements": 23, "totalPages": 3,
  "counts": { "all": 23, "upcoming": 5, "completed": 18, "organized": 20, "invited": 3 }
}
```

`counts` gives the size of every tab for the current search, so the client can label all its tabs from
one request.

**Dashboard stats**

`GET /api/meetings/stats?zone=Asia/Kolkata&months=6` returns `total`, `upcoming`, `completed`, `organized`,
`invited`, `last30Days`, `previous30Days` and `months` (oldest first, `[{ "month": "2026-10", "organized",
"invited", "participants" }]`). Months are calendar months in `zone` (an IANA time zone, default `UTC`);
`months` is 1–24 (default 6).

**Error format** (all errors):

```json
{
  "timestamp": "2026-10-24T12:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "fieldErrors": { "endTime": "End time must be after start time" }
}
```

| Status | When                                                                              |
|--------|-----------------------------------------------------------------------------------|
| 400    | Validation failure, malformed JSON, duplicate e-mails inside one create request   |
| 401    | Missing/invalid/expired token, wrong login credentials                             |
| 403    | Authenticated invitee trying to modify a meeting they don't organize              |
| 404    | Meeting/participant not found **or** not visible to you                           |
| 409    | E-mail already registered; participant already on the meeting; changing a meeting that has ended |
| 413    | Avatar larger than 2 MB                                                           |
| 415    | Avatar is not a PNG/JPEG/GIF/WebP (checked by declared type **and** file bytes)   |

## Authentication and authorization

1. **Signup** validates the input, lower-cases the e-mail, rejects duplicates (`409`), and stores
   `BCrypt(password)` (strength 10). The plaintext password is never stored or logged.
2. **Login** looks up the user by e-mail and checks the password with BCrypt. Unknown e-mail and wrong
   password return the same `401 Invalid email or password`, and an unknown e-mail is still checked
   against a dummy BCrypt hash, so neither the response nor its timing reveals which e-mails have accounts.
3. Both return `{ token, tokenType: "Bearer", expiresInSeconds, user }`. The token is an HS256 JWT with
   `sub` = user id, `iss` = `meeting-planner`, `iat` and `exp`. The default lifetime is **60 minutes**
   (`app.jwt.expiration-minutes`).
4. On every request, `JwtAuthenticationFilter` verifies the signature, issuer and expiry, then loads the
   user (tokens of deleted users stop working) and places an `AuthUser(id, email)` principal into the
   security context.
5. Every route except signup and login requires authentication. Unauthenticated requests get a JSON `401`.
6. Controllers get the current user via `@CurrentUser AuthUser`. **The owner of a meeting is always taken
   from the token, never from the request body.** Fields such as `createdBy` in a request are ignored, and
   a test covers this.
7. `MeetingService` enforces resource-level rules:
   - **organizer:** full access
   - **invitee** (account e-mail matches a participant e-mail): read-only. Modifying returns `403`.
   - **anyone else:** `404`, so the existence of other users' meetings is never revealed.
   - **completed meetings** (end time reached, judged by the server clock): `PUT` and adding or removing
     participants return `409 This meeting has already ended, so it can no longer be changed`, even for the
     organizer. The access checks above run first, so an invitee still gets `403`. `GET` responses include
     `completed: true`.

The session is stateless: no server sessions or cookies. CSRF protection is disabled because tokens are
sent in the `Authorization` header, not in cookies. Logging out is client-side (the frontend drops the
token), so a token stays valid until it expires (see Known limitations).

## Prerequisites

- **JDK 17 or newer** (`java -version`). Maven is **not** required: use the included wrapper.
- Internet access on the first build, to download dependencies.

## Local setup

### 1. Provide a JWT signing secret (required, never committed)

The app **refuses to start** without a secret of at least 32 characters and prints a clear error message.
Choose one option:

**Option A: environment variable**

```bash
# macOS / Linux / Git Bash
export JWT_SECRET="$(openssl rand -base64 48)"
```

```powershell
# Windows PowerShell
$env:JWT_SECRET = [Convert]::ToBase64String((1..48 | ForEach-Object { Get-Random -Maximum 256 }))
```

**Option B: git-ignored local file**

```bash
cp config/application-local.properties.example config/application-local.properties
# then edit app.jwt.secret in that file
```

### 2. Run the backend

```bash
./mvnw spring-boot:run          # macOS / Linux / Git Bash
mvnw.cmd spring-boot:run        # Windows cmd / PowerShell
```

The API is available at `http://localhost:8080`. Run the commands from the repository root, because the
database and upload folders are relative to it.

Alternatively, build a jar:

```bash
./mvnw package
java -jar target/meeting-planner-backend-0.1.0.jar
```

### 3. Run the frontend

See the frontend README (`npm install && npm run dev`, then open `http://localhost:5173`).

## Running tests

```bash
./mvnw test            # or: mvnw.cmd test
```

Tests use an **in-memory H2 database** and a test-only JWT secret from `src/test/resources`. They never
touch `./data` and don't need `JWT_SECRET` set.

| Test class                  | Type        | Covers                                                                    |
|-----------------------------|-------------|---------------------------------------------------------------------------|
| `AuthServiceTest`           | Unit        | Signup hashing with BCrypt, duplicate e-mail, login success/failure, constant-cost check for unknown e-mails |
| `JwtServiceTest`            | Unit        | Valid/expired/tampered/foreign-key tokens, missing or short secret        |
| `MeetingServiceTest`        | Unit        | Creation, duplicate participants, owner/invitee/stranger rules, participant limit, completed-meeting lock |
| `AvatarStorageServiceTest`  | Unit        | Valid upload, bad type, spoofed bytes, oversize, empty, path traversal    |
| `AuthIntegrationTest`       | Integration | Signup/login over HTTP, BCrypt hash in DB, 401 on missing/forged/expired token, CORS, security headers, H2 console off by default |
| `MeetingIntegrationTest`    | Integration | CRUD, validation (blank title, end ≤ start, bad e-mail), participants, ownership enforcement, completed meetings locked, paging, search, filters, stats, no N+1 queries |
| `AvatarIntegrationTest`     | Integration | Upload + download, old file removed after replacement, invalid type, oversize, auth required |

Last run: **73 tests, 0 failures, 0 errors** (`BUILD SUCCESS`).

## H2 database behavior

- Dev URL: `jdbc:h2:file:./data/meetingplanner`. Data **persists across restarts** in `./data/`
  (git-ignored).
- Schema is managed by Hibernate (`ddl-auto=update`). To start fresh, stop the app and delete the `data/`
  folder.
- `meetingplanner.mv.db` is a binary H2 file, so a text editor can't show its contents. Windows also
  locks it while the backend runs.
- **Viewing the data:** the H2 web console is **disabled** by default. To use it locally, add this line
  to the git-ignored `config/application-local.properties` and restart the backend:

  ```properties
  spring.h2.console.enabled=true
  ```

  Then open `http://localhost:8080/h2-console` and log in with:

  | Field     | Value                                 |
  |-----------|---------------------------------------|
  | JDBC URL  | `jdbc:h2:file:./data/meetingplanner`  |
  | User Name | `sa`                                  |
  | Password  | *(leave empty)*                       |

  Click a table on the left (`USERS`, `MEETINGS`, `PARTICIPANTS`) and press **Run**. The console only
  accepts connections from the same computer. Never enable it in a shared environment.

## Local image storage

- Avatars are saved to `./uploads/avatars/` (git-ignored, outside the source tree). You can change this
  with `app.storage.avatar-dir`.
- File names are server-generated (`<32 hex chars>.<ext>`). The uploaded file name is ignored, and the
  resolved path is checked to stay inside the upload directory.
- Accepted only if **both** the declared content type and the file's magic bytes are PNG, JPEG, GIF or
  WebP. The size limit is 2 MB (`app.storage.max-avatar-bytes` and Spring multipart limits).
- Images are served by the authenticated `GET /api/users/me/avatar`, not as public static files. When an
  avatar is replaced, the old file is deleted.

## Configuration reference

| Property                        | Default                 | Notes                                    |
|---------------------------------|-------------------------|------------------------------------------|
| `app.jwt.secret`                | `${JWT_SECRET}`         | **Required**, ≥ 32 bytes                 |
| `app.jwt.expiration-minutes`    | `60`                    | Token lifetime                           |
| `app.cors.allowed-origins`      | `http://localhost:5173` | Comma-separated list                     |
| `app.storage.avatar-dir`        | `./uploads/avatars`     | Created on first upload                  |
| `app.storage.max-avatar-bytes`  | `2097152`               | 2 MB                                     |

## Assumptions and design decisions

- **Participants are not user accounts.** They are a name and e-mail attached to one meeting. The same
  e-mail may appear in many meetings, but only once per meeting (case-insensitive).
- **Visibility:** organizers see and manage their meetings. Users whose account e-mail is on a participant
  list can view those meetings read-only. This felt more useful than "creator only" and costs one join.
- **Updating a meeting** (`PUT`) replaces title, description and times. Participants are managed only
  through their own endpoints, so a stale edit form can't wipe the participant list.
- **Meeting deletion** is included and allowed for the organizer only. It cascades to participants. It is
  still allowed after a meeting has ended, so organizers can clean up old meetings.
- **Completed meetings are read-only.** A meeting is complete from the instant its end time passes
  (`end <= now`); an ongoing meeting can still be edited. "Now" comes from an injectable `Clock` bean
  (`ClockConfig`), which tests replace with a fixed clock.
- **Start times in the past are allowed** (for example, to record a meeting that already happened). Only
  `end > start` is enforced. Such a meeting is complete as soon as it is saved, so include its participants
  in the create request.
- **404 rather than 403 for strangers**, so meeting IDs can't be probed.
- **Email invitations, calendar sync and video calls are out of scope**, as specified.
- Login does not normalize Unicode in passwords. The maximum password length is 72 because BCrypt ignores
  bytes beyond that.

## Known limitations

- No refresh tokens and no server-side token revocation. Logout only discards the token on the client.
- No rate limiting on login and signup (brute-force protection).
- No endpoints to change your name, e-mail or password, or to delete your account.
- Users can only download their own avatar, so other people are shown by initials.
- No overlap detection for meetings, no time-zone field per meeting (times are UTC instants), and no
  recurring meetings.
- A list request runs about 9 queries (the page, its count and five tab counts). Fine at this scale; a
  single grouped count query would be the next step.
- H2 in file mode allows one running backend at a time. `ddl-auto=update` is convenient for a prototype; a
  real deployment would use Flyway or Liquibase migrations and PostgreSQL or MySQL.
- Avatar files are validated by signature only, not decoded or re-encoded, so image metadata (EXIF) is
  kept, and they live on the local disk.

## Future improvements

Refresh tokens with rotation and revocation, login rate limiting or lockout, profile and password
editing, e-mail invitations with RSVP, linking participants to registered users, overlap warnings,
calendar (ICS) export, image re-encoding/resizing, OpenAPI/Swagger docs, Flyway migrations with
PostgreSQL, Docker Compose, and CI running both test suites.

## Security considerations

- Passwords: BCrypt only. Never returned in responses (DTOs exclude them) and never logged.
- JWT secret: supplied via environment or a git-ignored file. A missing or weak secret is a hard startup
  failure. The test secret exists only under `src/test`.
- Authorization is enforced server-side on every meeting and profile operation. The frontend's route
  guards are only a convenience.
- Input validation uses Bean Validation on all DTOs, plus a class-level `@ValidTimeRange`.
- Error responses never include stack traces or exception details (`server.error.include-*` disabled, and
  a catch-all handler returns a generic 500). Unexpected errors are logged server-side only.
- CORS allows only `http://localhost:5173`, with methods and headers restricted.
- Security headers: `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`,
  `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, plus
  Spring Security's default no-cache headers (the avatar endpoint sets a short private cache instead).
- Uploads: type allowlist, magic-byte check, size cap, generated names, path containment, and an
  authenticated download.
- Git: `data/`, `uploads/`, `config/application-local.properties`, `.env*` and `target/` are ignored.

## Troubleshooting

| Problem                                              | Fix                                                                         |
|------------------------------------------------------|-----------------------------------------------------------------------------|
| `JWT signing secret is not configured`               | Set `JWT_SECRET` or create `config/application-local.properties` (step 1)    |
| `JWT signing secret is too short`                    | Use at least 32 characters                                                  |
| `release version 17 not supported` / class version errors | `JAVA_HOME` points to an older JDK. Install or select JDK 17+           |
| `Port 8080 already in use`                           | Stop the other process or run with `--server.port=8081` (and set `VITE_BACKEND_URL` in the frontend) |
| `Database may be already in use`                     | Another instance is running from the same folder. Stop it                   |
| Browser shows CORS errors                            | Use the Vite dev server on port 5173, or add your origin to `app.cors.allowed-origins` |
| Want a clean slate                                   | Stop the app, delete `data/` and `uploads/`                                 |
