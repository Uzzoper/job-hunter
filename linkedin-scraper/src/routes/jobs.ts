/**
 * Jobs router — handles /api/jobs endpoints.
 *
 * Dependency injection: receives SearchScraper and DetailScraper instances
 * so callers can inject mocks in tests.
 */

import { Router, type Request, type Response } from "express";
import type { ApiResponse, JobCard, JobDetail } from "../types.js";
import { SearchScraper } from "../scrapers/search.js";
import { DetailScraper } from "../scrapers/detail.js";

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Map scraper errors to HTTP status codes and structured JSON responses.
 * Must correspond to the error messages thrown by the scrapers.
 */
function handleScraperError(res: Response, error: unknown): void {
  const message = error instanceof Error ? error.message : String(error);

  // Browser not ready
  if (
    message.includes("Browser is not launched") ||
    message.includes("Failed to launch Chromium")
  ) {
    const body: ApiResponse<never> = {
      success: false,
      error: { code: "SERVICE_UNAVAILABLE", message: "Browser is not ready" },
    };
    res.status(503).json(body);
    return;
  }

  // Bot challenge detected → rate limit
  if (message.includes("Bot challenge detected")) {
    const body: ApiResponse<never> = {
      success: false,
      error: {
        code: "RATE_LIMITED",
        message: "LinkedIn bot challenge detected. Please try again later.",
      },
    };
    res.status(429).json(body);
    return;
  }

  // Timeout (navigation or scraping)
  if (
    message.toLowerCase().includes("timeout") ||
    message.toLowerCase().includes("timed out")
  ) {
    const body: ApiResponse<never> = {
      success: false,
      error: { code: "GATEWAY_TIMEOUT", message: "Scraper timed out" },
    };
    res.status(504).json(body);
    return;
  }

  // Fallback: internal error
  console.error("[jobs] Unhandled scraper error:", message);
  const body: ApiResponse<never> = {
    success: false,
    error: { code: "INTERNAL_ERROR", message: "An unexpected error occurred" },
  };
  res.status(500).json(body);
}

// ---------------------------------------------------------------------------
// Router factory
// ---------------------------------------------------------------------------

/**
 * Create an Express Router for job-related endpoints.
 *
 * @param searchScraper  Instance of SearchScraper (or mock)
 * @param detailScraper  Instance of DetailScraper (or mock)
 */
export function createJobsRouter(
  searchScraper: SearchScraper,
  detailScraper: DetailScraper,
): Router {
  const router = Router();

  // -----------------------------------------------------------------------
  // GET /api/jobs?keywords=...&location=...
  // -----------------------------------------------------------------------
  router.get("/", async (req: Request, res: Response) => {
    const { keywords, location } = req.query;

    // Validate required keywords param
    if (
      !keywords ||
      typeof keywords !== "string" ||
      keywords.trim().length === 0
    ) {
      const body: ApiResponse<never> = {
        success: false,
        error: { code: "VALIDATION_ERROR", message: "keywords is required" },
      };
      res.status(400).json(body);
      return;
    }

    try {
      const locationStr =
        typeof location === "string" && location.trim().length > 0
          ? location.trim()
          : undefined;

      const jobs = await searchScraper.search(keywords.trim(), locationStr);

      const body: ApiResponse<JobCard[]> = { success: true, data: jobs };
      res.json(body);
    } catch (error) {
      handleScraperError(res, error);
    }
  });

  // -----------------------------------------------------------------------
  // GET /api/jobs/:jobId
  // -----------------------------------------------------------------------
  router.get("/:jobId", async (req: Request, res: Response) => {
    const { jobId } = req.params;

    // Validate that jobId is numeric
    if (!/^\d+$/.test(jobId)) {
      const body: ApiResponse<never> = {
        success: false,
        error: {
          code: "VALIDATION_ERROR",
          message: "jobId must be a numeric value",
        },
      };
      res.status(400).json(body);
      return;
    }

    try {
      const detail = await detailScraper.getDetail(Number(jobId));

      if (!detail) {
        const body: ApiResponse<never> = {
          success: false,
          error: {
            code: "NOT_FOUND",
            message: `Job ${jobId} not found (login wall detected)`,
          },
        };
        res.status(404).json(body);
        return;
      }

      const body: ApiResponse<JobDetail> = { success: true, data: detail };
      res.json(body);
    } catch (error) {
      handleScraperError(res, error);
    }
  });

  return router;
}
