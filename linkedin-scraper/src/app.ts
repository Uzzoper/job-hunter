import express, { type Request, type Response, type NextFunction } from "express";
import cors from "cors";
import type { ApiResponse } from "./types.js";
import { BrowserManager } from "./services/browser.js";
import { SearchScraper } from "./scrapers/search.js";
import { DetailScraper } from "./scrapers/detail.js";
import { createHealthRouter } from "./routes/health.js";
import { createJobsRouter } from "./routes/jobs.js";

// ---------------------------------------------------------------------------
// Dependency injection container
// ---------------------------------------------------------------------------

export interface AppDependencies {
  searchScraper?: SearchScraper;
  detailScraper?: DetailScraper;
  browserManager?: BrowserManager;
}

// ---------------------------------------------------------------------------
// App factory
// ---------------------------------------------------------------------------

/**
 * Create the Express application.
 *
 * @param deps  Optional dependency overrides (for testing)
 */
export function createApp(deps?: AppDependencies) {
  const app = express();

  // Resolve dependencies: use injected or default to real singletons
  const browserManager = deps?.browserManager ?? BrowserManager.getInstance();
  const searchScraper =
    deps?.searchScraper ?? new SearchScraper(browserManager);
  const detailScraper =
    deps?.detailScraper ?? new DetailScraper(browserManager);

  // -----------------------------------------------------------------------
  // Middleware
  // -----------------------------------------------------------------------

  app.use(express.json());
  app.use(cors());

  // Request logging: method, path, status, duration
  app.use((req: Request, res: Response, next: NextFunction) => {
    const start = Date.now();

    res.on("finish", () => {
      const duration = Date.now() - start;
      console.log(`${req.method} ${req.path} ${res.statusCode} ${duration}ms`);
    });

    next();
  });

  // -----------------------------------------------------------------------
  // Routes
  // -----------------------------------------------------------------------

  app.use("/health", createHealthRouter(browserManager));
  app.use("/api/jobs", createJobsRouter(searchScraper, detailScraper));

  // POST /api/jobs → method not allowed (GET routes catch /api/jobs/*
  // but POST to the exact /api/jobs falls through to here)
  app.post("/api/jobs", (_req: Request, res: Response) => {
    const body: ApiResponse<never> = {
      success: false,
      error: { code: "METHOD_NOT_ALLOWED", message: "Method not allowed" },
    };
    res.status(405).json(body);
  });

  // -----------------------------------------------------------------------
  // Fallback handlers
  // -----------------------------------------------------------------------

  // 404 — unknown route
  app.use((_req: Request, res: Response) => {
    const body: ApiResponse<never> = {
      success: false,
      error: { code: "NOT_FOUND", message: "Route not found" },
    };
    res.status(404).json(body);
  });

  // 500 — unhandled error
  app.use((err: Error, _req: Request, res: Response, _next: NextFunction) => {
    console.error("Unhandled error:", err);
    const body: ApiResponse<never> = {
      success: false,
      error: { code: "INTERNAL_ERROR", message: "An unexpected error occurred" },
    };
    res.status(500).json(body);
  });

  return app;
}
