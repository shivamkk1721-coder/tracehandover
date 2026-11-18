# TraceHandover

TraceHandover helps a team prepare a reviewed, evidence-linked handover when a member leaves. A coordinator can collect source passages, review suggestions, approve a version and give a recipient access. The recipient can read approved information and acknowledge receipt.

## Try the public sample

1. Open the deployed site and choose **Try the sample project**. No account or Gemini API key is needed for the built-in synthetic sample.
2. Read the source passages and the review notes. The sample deliberately includes a missing owner and two different statuses for the same task.
3. Correct the fields, choose the source passages that support each claim, and tick **Include this item** for items you want in the handover. Review notes are shared only when you select that option.
4. Select **Create approved handover** to view the result. You can print it or save it as a PDF. The public sample result is temporary and belongs to your browser session.

## Coordinator and recipient workflow

1. Create an account and sign in. The coordinator creates a project and enters the departing member's name.
2. The coordinator uploads authorized UTF-8 `.txt`, `.md` or `.csv` files, chooses how to group passages, and reviews the suggestions. Uploaded text may be sent to Gemini if an API key is configured.
3. The coordinator edits claims and explicitly selects which source passages support each field. Selecting a passage does not automatically prove the claim.
4. The coordinator selects items for approval. Each approval creates a separate version; prior approved versions remain available. The change summary compares items by position.
5. The recipient creates an account. The coordinator adds that username to the project after checking its identity out of band. The recipient can read approved versions and acknowledge each one. The coordinator can remove recipient access.

## Run locally

Requirements: Java 21, internet access for Maven dependencies, and a terminal at the repository root. The Maven wrapper is included; Maven does not need to be installed separately.

In Windows PowerShell:

```powershell
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

On macOS or Linux, use `./mvnw test` and `./mvnw spring-boot:run`. Open <http://localhost:8080/>. An H2 database file named `tracehandover-data.mv.db` is created in the project directory and excluded from Git. It persists across local restarts, provided the file is retained.

Gemini is optional. To use AI suggestions, set your own API key before starting the app. For example, in PowerShell:

```powershell
$env:GEMINI_API_KEY = "YOUR_PRIVATE_KEY"
.\mvnw.cmd spring-boot:run
```

Replace the placeholder locally and never commit or share the key. If Gemini is unavailable or returns an error, source grouping and manual review are still available.

## Persistent deployment with PostgreSQL

The default H2 file is appropriate for local development. A container's local filesystem can be replaced, so configure an external PostgreSQL database before relying on deployment persistence.

For a Render deployment, create a PostgreSQL database in the same region as the web service and set these environment variables in the web service's settings:

| Variable | Value |
| --- | --- |
| `JDBC_DATABASE_URL` | `jdbc:postgresql://<internal-host>:5432/<database-name>` using your database's internal host and name |
| `DB_USERNAME` | Your database username |
| `DB_PASSWORD` | Your database password |
| `COOKIE_SECURE` | `true` for an HTTPS deployment |
| `GEMINI_API_KEY` | Your private Gemini key, if you want AI suggestions |

The JDBC URL format differs from a `postgresql://` connection string. Copy the host, database, username and password into their respective fields, without placing credentials in Git or a public document. The app creates its tables at startup. Free databases can expire and may lack backups; check the provider's current plan details and keep a backup if you need to retain data. Test persistence by creating an approved version, restarting the service, signing in again, and opening the version.

## Architecture

- Java 21 and Spring Boot serve Thymeleaf pages and handle authentication, project membership, review and approval.
- Spring Security provides form login, password hashing and CSRF protection. Project authorization is checked on the server for each private action.
- Source passages receive IDs. The reconciler suggests groups and highlights missing or differing structured values; Gemini can supply additional unverified suggestions. A reviewer chooses the values and evidence to share.
- Spring JDBC stores accounts, project membership, immutable approved snapshots, change summaries and acknowledgements in H2 locally or PostgreSQL when configured. Review drafts remain in the current session.
- A Dockerfile builds the Java application for deployment.

## Testing

Run `./mvnw test` (or `.\mvnw.cmd test` on Windows). The tests cover source grouping, contradictory and missing values, source references, version comparisons, project access checks, repeat approvals, acknowledgements, account registration and the public review flow. After deployment, also test both roles, an unrelated account, a service restart and the public sample in a private browser window.

## Current limitations

- This is a prototype for synthetic or authorized non-sensitive data, not a production identity or security system. Accounts have no email verification, password recovery or login rate limiting; recipients' identities must be checked outside the app.
- Uploads are limited to ten UTF-8 files, 40 non-empty lines and 16,000 characters in total. CSV is processed one line at a time; quoted fields spanning multiple lines are not supported. Structured extraction expects the header `Task,Owner,Status,Next step` or a recognized variant of the final column.
- Grouping and conflict flags are suggestions, not a complete semantic comparison. A reviewer can keep passages separate and must check all claims and evidence. AI output can be wrong or unavailable.
- Change summaries compare item positions, so reordering can make the summary less useful. Drafts and public sample results are session-only; approved versions require a configured durable database on deployment.
- Source uploads may be sent to Google Gemini when its API key is configured. Do not upload private or sensitive information to the public demo.

## AI assistance

ChatGPT assisted with planning and portions of the code. The application can use the Gemini API to generate suggestions for human review.
