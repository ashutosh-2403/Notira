
# Notira 🔄
### Real-time Jira → Notion Sync Engine

> **Jira tracks your work. Notion documents it. Notira syncs them automatically — so your sprint documentation writes itself.**

[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-green)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7-red)](https://redis.io/)
[![Docker](https://img.shields.io/badge/Docker-Compose-blue)](https://www.docker.com/)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

---

## 🧩 The Problem

Every engineering team faces the same painful split:

- **Jira** is where work happens — tickets, sprints, status updates
- **Notion** is where knowledge lives — docs, wikis, project pages

But keeping them in sync is **manual, tedious, and always out of date**. Engineers update a Jira ticket and forget to update Notion. PMs check Notion for status and see stale data. Documentation lags weeks behind reality.

**No one has built a self-hosted, open-source bridge between them. Until now.**

---

## ✨ What Notira Does

The moment you touch a Jira ticket, Notira automatically:

| Jira Action | Notion Result |
|---|---|
| Create a ticket | Creates a structured Notion page with AI summary |
| Update description | Updates description in place |
| Move to In Progress / Done | Updates the Stage Tracker live |
| Change assignee or priority | Logs to Change History |
| Add a comment | Appends to Comments section |
| Attach an image | Logs attachment in history |

Every Notion page has a clean, consistent structure — automatically.

---

## 📄 Notion Page Structure

Every synced Jira ticket creates a Notion page like this:


💡 AI Summary — auto-generated from ticket description
─────────────────────────────
📋 Details
  • 🔑 Jira Key:   SCRUM-42
  • 📌 Status:     In Progress
  • 👤 Assignee:   John Doe
  • 📢 Reporter:   Jane Smith
  • 🚨 Priority:   High
  • 🏷️ Type:       Task
─────────────────────────────
🚦 Stage Tracker
  ⚪ Backlog  →  🟡 In Progress  →  ⚪ In Review  →  ⚪ Done
─────────────────────────────
📝 Description
  [live synced from Jira]
─────────────────────────────
💬 Comments
  [auto-appended from Jira]
─────────────────────────────
🕐 Change History
  ▶ Apr 05, 2026 10:30 · John Doe
      🔄 Status: To Do → In Progress
  ▶ Apr 05, 2026 11:15 · Jane Smith
      📝 Description updated
      Before: ...
      After:  ...


---

## 🏗️ Architecture


┌─────────────────┐         ┌──────────────────────┐         ┌──────────────────┐
│   Jira Cloud    │──webhook▶│   Spring Boot App    │──API──▶│   Notion API     │
│                 │         │                      │         │                  │
│  Issue Created  │         │  JiraWebhookService  │         │  Creates pages   │
│  Issue Updated  │         │  SyncProcessorSvc    │         │  Updates blocks  │
│  Status Changed │         │  JiraToNotionTransf  │         │  Appends history │
│  Comment Added  │         │  NotionWriterService │         │                  │
└─────────────────┘         └──────────┬───────────┘         └──────────────────┘
                                       │
                            ┌──────────▼───────────┐
                            │   PostgreSQL + Redis  │
                            │                      │
                            │  sync_mappings        │
                            │  sync_events          │
                            │  sync_errors          │
                            └──────────────────────┘


**Tech Stack:**
- **Java 21** + **Spring Boot 3.5** — core application
- **PostgreSQL** — stores Jira ↔ Notion mappings and event audit log
- **Redis** — event queue with dead letter support
- **Flyway** — database migrations
- **OkHttp** — Notion API calls
- **Docker Compose** — one-command deployment
- **ngrok** — webhook tunneling for local dev

---

## 🚀 Quick Start

### Prerequisites
- Docker + Docker Compose
- A Jira Cloud account
- A Notion account
- ngrok (for local dev) or a public server

### 1. Clone the repo

bash
git clone https://github.com/ashutosh-2403/notira
cd notira


### 2. Configure environment

bash
cp .env.example .env


Edit '.env' with your credentials:

env
# Jira
JIRA_BASE_URL=https://yourcompany.atlassian.net
JIRA_EMAIL=your@email.com
JIRA_API_TOKEN=your-jira-api-token

# Webhook security
JIRA_WEBHOOK_SECRET=any-random-string

# Notion
NOTION_API_TOKEN=your-notion-integration-token
NOTION_DATABASE_ID=your-notion-database-id


### 3. Run with Docker

bash
docker compose up -d


That's it. The app starts on 'http://localhost:8080'.

---

## ⚙️ Setup Guide

### Step 1 — Get your Jira API Token

1. Go to [https://id.atlassian.com/manage-profile/security/api-tokens](https://id.atlassian.com/manage-profile/security/api-tokens)
2. Click **Create API token**
3. Copy the token into your '.env'

### Step 2 — Create a Notion Integration

1. Go to [https://www.notion.so/my-integrations](https://www.notion.so/my-integrations)
2. Click **New integration** → name it `notira`
3. Copy the **Internal Integration Secret** into your '.env'

### Step 3 — Create a Notion Database

1. In Notion, create a new **full-page database** (Empty database)
2. Open it → click `...` top right → **Connections** → add 'notira'
3. Copy the database ID from the URL:
   
   https://notion.so/YOUR-DATABASE-ID?v=...
   
4. Paste into your '.env' as 'NOTION_DATABASE_ID'

### Step 4 — Register Jira Webhook

1. Go to: 'https://yourcompany.atlassian.net/plugins/servlet/webhooks'
2. Click **Create a WebHook**
3. Set URL to: 'https://your-server.com/api/webhooks/jira'
4. Check all **Issue** events: created, updated, deleted
5. Check **Comment** events: created
6. Save

### Step 5 — Expose locally (dev only)

bash
ngrok http 8080
# Use the https://xxx.ngrok-free.app URL as your webhook URL
---

## 🔧 Development Setup

### Running locally without Docker

bash
# Start only PostgreSQL and Redis via Docker
docker compose up -d postgres redis

# Run the app
JIRA_BASE_URL=https://yourcompany.atlassian.net \
JIRA_EMAIL=your@email.com \
JIRA_API_TOKEN=your-token \
JIRA_WEBHOOK_SECRET=your-secret \
NOTION_API_TOKEN=your-notion-token \
NOTION_DATABASE_ID=your-database-id \
./mvnw spring-boot:run


### Project structure

src/main/java/com/ashutosh/jiranotionsync/
├── controller/
│   └── JiraWebhookController.java     # Receives Jira webhooks
├── service/
│   ├── JiraWebhookService.java        # Parses and queues events
│   ├── SyncProcessorService.java      # Orchestrates sync logic
│   ├── NotionWriterService.java       # Notion API calls
│   └── SyncQueueService.java          # Redis queue management
├── transform/
│   └── JiraToNotionTransformer.java   # Jira → Notion format
├── entity/
│   ├── SyncMapping.java               # Jira ↔ Notion mapping
│   ├── SyncEvent.java                 # Event audit log
│   └── SyncError.java                 # Dead letter store
└── dto/
    ├── JiraWebhookPayload.java        # Jira webhook DTO
    └── NotionPageRequest.java         # Notion API DTO


---

## 🌍 Deploying to Production

### Deploy on any VPS (Ubuntu)

bash
# Clone and configure
git clone https://github.com/ashutosh-2403/notira
cd notira
cp .env.example .env
# Edit .env with your credentials

# Build and run
docker compose up -d --build

# Check logs
docker compose logs -f app


Point your Jira webhook to 'https://your-server-ip:8080/api/webhooks/jira'.

---

## 🤔 Why Notira is Unique

| Feature | Notira | Zapier/Make | Jira native docs |
|---|---|---|---|
| Self-hosted | ✅ | ❌ | ❌ |
| Free | ✅ | ❌ paid | ❌ |
| In-place updates | ✅ | ❌ | ❌ |
| Change history toggle | ✅ | ❌ | ❌ |
| Stage tracker | ✅ | ❌ | ❌ |
| Open source | ✅ | ❌ | ❌ |
| No data leaves your server | ✅ | ❌ | ❌ |

---

## 🗺️ Roadmap

- [x] AI-generated summaries in the callout box (Notion AI)
- [ ] Two-way sync — Notion → Jira
- [ ] Slack notifications on sync
- [ ] GitHub Actions CI/CD pipeline
- [ ] GCP Cloud Run deployment guide
- [ ] Custom Notion page templates

---

## 🤝 Contributing

Pull requests are welcome! For major changes please open an issue first.

bash
# Fork the repo
git checkout -b feature/your-feature
git commit -m "Add your feature"
git push origin feature/your-feature
# Open a Pull Request


---

## 📄 License

MIT License — see [LICENSE](LICENSE) for details.

---

## 👤 Author

**Ashutosh Mishra**
- GitHub: [@ashutosh-2403](https://github.com/ashutosh-2403)
- LinkedIn: [ashutoshmishra2403](https://linkedin.com/in/ashutoshmishra2403)

---

<p align="center">
  Built with ❤️ by Ashutosh Mishra · Final Year CS @ PDEU
</p>
