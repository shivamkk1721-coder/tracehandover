# TraceHandover

TraceHandover helps student teams prepare a handover when a member leaves.
It turns project notes into suggestions that a person can review against
their original sources before approving a handover.

## Try the demo

1. Open the deployed project link.
2. Click **Try sample project**.
3. Check the suggestions against their source passages and edit if needed.
4. Select the items to include and click **Create handover**.
5. Use **Print / Save as PDF** to save the approved handover.

## Features

- Upload `.txt`, `.md`, and `.csv` files.
- Assign source IDs to non-empty lines.
- Use Gemini to suggest tasks, owners, statuses, next actions and review notes.
- Check that each suggestion's main source ID exists.
- Let a person review, edit and select items before approval.
- Show approved items with their source text.

## Technology

- Java 21 and Spring Boot
- Thymeleaf
- Gemini API
- Maven and Docker

## Run locally

Java 21 is required. Set `GEMINI_API_KEY` in your environment without
adding the actual key to the source code.

On Windows PowerShell:

```powershell
$env:GEMINI_API_KEY = "YOUR_API_KEY"
.\mvnw.cmd spring-boot:run
```

Replace `YOUR_API_KEY` with your own private key, then open
`http://localhost:8080/`.

## Current limitations

- CSV files are read line by line rather than fully parsed.
- AI suggestions and conflict warnings can be wrong. A valid source ID
  does not prove that a claim is supported.
- If Gemini is unavailable, sources can still be reviewed manually.
- Handover data is temporary and the demo has no login or role enforcement.
- Use sample data: uploaded text may be sent to Google Gemini.

## AI assistance

ChatGPT assisted with planning and portions of the code. The application
uses the Gemini API to generate handover suggestions.