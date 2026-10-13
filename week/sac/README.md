# Smart Academic Companion 🎓

A **WhatsApp-integrated student academic management and collaborative revision platform**
for the University of Eastern Africa, Baraton (UEAB) — BBIT final-year project.

> Re-implementation of the Laravel proposal in **Java 17 + Spring Boot 3 + PostgreSQL**.

## Modules (all 6 from the proposal)

| Module | Route | What it does |
|---|---|---|
| **Schedule Core** | `/schedule` | Month calendar for classes, CATs, exams; add/delete events |
| **Revision Engine** | `/notes` | Upload Word / PowerPoint / Excel / PDF / TXT; auto-parses, summarizes & structures for revision |
| **WhatsApp Auto-Sync** | `/whatsapp` | Webhook endpoint + built-in simulator; extracts CAT/exam/deadline events from messages into the calendar |
| **Course Cohort Network** | `/courses` | Auto-groups students registered for the same units |
| **Study Sessions** | `/sessions` | Group revision sessions with participants & shared material |
| **Q&A Chatbox** | `/qa` | Course-specific questions and peer answers |

Plus: dashboard, registration with course-code auto-enrollment, Spring Security login (BCrypt).

## Prerequisites

- Java 17 ✓
- Maven 3.9 ✓
- PostgreSQL 18 (installed and running as a Windows service)

## Setup (first time)

1. **Create the database** (set your real postgres password first):

   ```bash
   DB_PASSWORD='your-postgres-password' bash setup-db.sh
   ```

2. **Put the same password** in `src/main/resources/application.yml`:

   ```yaml
   spring.datasource.password: your-postgres-password
   ```

3. *(Optional)* Enable AI structuring with any OpenAI-compatible API:

   ```yaml
   app:
     llm:
       enabled: true
       api-url: https://api.openai.com/v1/chat/completions
       api-key: "sk-..."
       model: gpt-4o-mini
   ```

   Without a key, the built-in offline summarizer/date-extractor is used —
   everything still works.

## Run

```bash
bash run.sh
# or
mvn spring-boot:run
```

Open **http://localhost:8080** → log in as `admin / admin123` (or register a new student
account with course codes like `BIT 2104, BIT 2105` to auto-join cohorts).

## Demo flow for the presentation

1. Log in → **Dashboard** shows courses, cohort size, upcoming events.
2. **WhatsApp Sync** → send the pre-filled simulator message → see `PARSED`.
3. Go to **Schedule** → the CAT event appears on the calendar marked `WhatsApp`.
4. **Revision** → upload any `.docx/.pptx/.xlsx/.pdf` → open it to see summary + structured notes.
5. **Courses** → see cohort mates; **Sessions** → create/join a revision session.
6. **Q&A** → ask a question, answer from another account.

## Real WhatsApp integration (production)

POST messages from a WhatsApp Business API webhook or a Baileys bridge to:

```
POST /whatsapp/webhook/{ownerUsername}?token=sac-secret-token
Content-Type: application/json
{"from": "Sender Name", "text": "CAT 1 on 25/09/2026 at 10am in Lab B"}
```

Change the token via `app.whatsapp.webhook-token` in `application.yml`.

## Tests

```bash
mvn test
```

## Project structure

```
src/main/java/com/ueab/sac/
├── config/        WebConfig, DataSeeder (pilot courses + admin)
├── controller/    Auth, Dashboard, Schedule, Courses, Notes, Sessions, QA, WhatsApp
├── model/         JPA entities (User, Course, Enrollment, ScheduleEvent, Note,
│                  StudySession, SessionParticipant, Question, Answer, WhatsAppEventLog)
├── repo/          Spring Data repositories
├── security/      SecurityConfig, CustomUserDetailsService, CurrentUser
└── service/
    ├── parse/     Word, PowerPoint, Excel, PDF, TXT extractors + facade
    ├── whatsapp/  Event parser (regex/LLM) + sync service
    ├── LlmClient       (optional OpenAI-compatible API)
    └── SummarizationService (LLM + offline extractive fallback)
```
