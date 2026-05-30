# Specification: Job Hunter Frontend (Angular 19 + Angular Material + Tailwind v4)

This document contains the complete specification, API contracts, dependencies, and architectural guidelines for the Angular frontend of the **Job Hunter** application.

---

## 1. Overview & Architecture

The frontend is a single-page application built with **Angular 19**, utilizing **standalone components**, native **Signals**, **Angular Material** for core UI elements, and **Tailwind CSS v4** for modern utility-first layout styling.

It communicates with the Job Hunter Spring Boot API (`http://localhost:8080`) using an Angular development proxy to route all `/api` calls.

### Folder Structure (Recommended)
```
src/
├── app/
│   ├── core/                  → singleton services & interceptors
│   │   ├── services/
│   │   │   └── job.service.ts
│   │   └── interceptors/
│   │       └── api.interceptor.ts
│   ├── shared/                → reusable pipes & utilities
│   │   └── pipes/
│   │       └── safe-html.pipe.ts
│   ├── features/              → smart/container components (pages)
│   │   ├── job-list/
│   │   │   ├── components/
│   │   │   │   └── job-card/
│   │   │   ├── job-list.component.ts
│   │   │   ├── job-list.component.html
│   │   │   └── job-list.component.css
│   │   └── job-detail/
│   │       ├── job-detail.component.ts
│   │       ├── job-detail.component.html
│   │       └── job-detail.component.css
│   ├── app.config.ts          → application providers (routes, HTTP client with signals)
│   ├── app.routes.ts          → routing definition (/jobs and /jobs/:id)
│   └── app.component.ts       → main shell
├── assets/                    → static assets
├── index.html
├── main.ts
└── styles.css                 → tailwind CSS & global material variables
```

---

## 2. Dependencies & Setup

Run these commands in the root of the new frontend repository:

```bash
# Install Angular Material
npx ng add @angular/material --skip-confirmation

# Install Tailwind CSS v4 & PostCSS
npm install tailwindcss @tailwindcss/postcss postcss
```

### PostCSS Configuration
Create a `postcss.config.js` in the root of your project:
```javascript
module.exports = {
  plugins: {
    '@tailwindcss/postcss': {},
  }
}
```

### Main Stylesheet Setup (`src/styles.css`)
Import Tailwind and custom Google Fonts:
```css
@import "tailwindcss";
@import "@angular/material/prebuilt-themes/purple-green.css";

/* Google Font Integration */
@import url('https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;500;600;700&display=swap');

body {
  font-family: 'Outfit', sans-serif;
  background-color: #0f172a; /* Tailwind bg-slate-900 */
  color: #f8fafc;            /* Tailwind text-slate-50 */
  margin: 0;
}
```

---

## 3. Routing Map

The application features full router integration to represent pages and support direct bookmarks:

- `/` (root) → redirects to `/jobs`
- `/jobs` → **JobList** page (list of job cards, filters, and statistics header)
- `/jobs/:id` → **JobDetail** page (selected job full details, AI analysis circular score indicator, and personalized email generator area)

---

## 4. TypeScript Interfaces (Core Models)

### Job Model
```typescript
export interface Job {
  id: number;
  title: string;
  company: string;
  url: string;
  description: string;
  postedAt: string; // ISO Date YYYY-MM-DD
  matchScore: number | null; // null if pending analysis
}
```

### JobAnalysis Model
```typescript
export type CompanyTone = 'FORMAL' | 'CASUAL' | 'STARTUP';

export interface JobAnalysis {
  matchScore: number;
  matchedSkills: string[];
  missingSkills: string[];
  companyTone: CompanyTone;
  summary: string;
}
```

### EmailDraft Model
```typescript
export type EmailStatus = 'PENDING' | 'SENT';

export interface EmailDraft {
  id: number;
  jobId: number;
  subject: string;
  body: string;
  status: EmailStatus;
  generatedAt: string;
}
```

---

## 5. REST API Endpoints

In development, the API base URL is mapped using the proxy to `/api`.

| Method | Endpoint | Request Payload | Response Model | Description |
| :--- | :--- | :--- | :--- | :--- |
| **GET** | `/api/jobs` | *None* | `Job[]` | Retrieves all scraped jobs. |
| **GET** | `/api/jobs/{id}` | *None* | `Job` | Retrieves a specific job by ID. |
| **POST** | `/api/jobs/{id}/analyze` | *None* | `JobAnalysis` | Triggers AI analysis on the job. |
| **GET** | `/api/jobs/{id}/email` | *None* | `EmailDraft` | Retrieves the email draft for this job. |
| **POST** | `/api/jobs/{id}/email` | *None* | `EmailDraft` | Generates a new email draft. |
| **POST** | `/api/jobs/fetch` | *None* | `{ message: string }` | Manually triggers the web scrapers. |

---

## 6. UI Guidelines (Tailwind CSS + Angular Material)

To build a premium dark-themed portfolio project, style Angular Material components with Tailwind utility classes:

- **Layout Structure**: Use Tailwind flex/grid layout helpers to build clean layouts.
- **JobList Screen**:
  - A clean header containing application title, statistics summary, and a search input (`matFormField` with `matInput` and search icon).
  - Material cards (`mat-card`) styled with `bg-slate-800/80 hover:border-indigo-500 transition-all border border-slate-700/60 shadow-xl rounded-xl`.
  - A sync button `[Buscar Vagas 🔄]` (`mat-icon-button` or `mat-raised-button`) triggering manual scraping.
- **JobDetail Screen**:
  - A back link/button (`mat-icon-button` with `arrow_back`) to return to `/jobs`.
  - Match Score Progress: A circular indicator (`mat-progress-spinner` or custom SVG radial indicator) showing the match score percentage. Color classes:
    - `>= 80`: Green (`text-emerald-400`)
    - `50-79`: Yellow/Amber (`text-amber-400`)
    - `< 50`: Red (`text-rose-400`)
  - Skill chips: Material Chips (`mat-chip-set`) displaying matching skills in green and missing skills in red.
  - Action buttons: "Analyze Job" (only shown if `matchScore` is null), and "Generate Email" (available once analyzed).
  - Email draft area: A clean Material card with inputs for Subject and Body, alongside a "Copy Email" button with a snack-bar confirmation.
- **Loading States**: Use Angular 19 **`@defer`** block in the templates:
  - Wrap components in `@defer (on immediate) { ... } @loading { <skeleton-screen /> }` to simulate modern streaming render styles and present beautiful skeleton frames using Tailwind's `animate-pulse` class.

---

## 7. Angular Signals State Management

The application state should be managed reactively using **Signals** in `JobService` or state files:

- `jobs = signal<Job[]>([])`
- `selectedJob = signal<Job | null>(null)`
- `loading = signal<boolean>(false)`
- `searchQuery = signal<string>('')`
- `minScoreFilter = signal<number | null>(null)`
- `filteredJobs = computed(() => { ... })` - computes and filters the list of jobs reactively based on the query and minScore signal changes.

---

## 8. Development Proxy Configuration (`proxy.conf.json`)

Create this file in the root of the Angular project:
```json
{
  "/api": {
    "target": "http://localhost:8080",
    "secure": false,
    "logLevel": "debug"
  }
}
```
Update `angular.json` under the `serve` options:
```json
"options": {
  "proxyConfig": "proxy.conf.json"
}
```
