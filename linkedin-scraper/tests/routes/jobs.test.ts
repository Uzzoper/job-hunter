import { describe, it, expect, jest, beforeEach } from "@jest/globals";
import express from "express";
import request from "supertest";
import { createJobsRouter } from "../../src/routes/jobs.js";
import type { JobCard, JobDetail } from "../../src/types.js";

// ---------------------------------------------------------------------------
// Mock scrapers
// ---------------------------------------------------------------------------

const mockSearchScraper = {
  search: jest.fn<(...args: string[]) => Promise<JobCard[]>>(),
};

const mockDetailScraper = {
  getDetail: jest.fn<(jobId: number) => Promise<JobDetail | null>>(),
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function createApp() {
  const app = express();
  app.use(express.json());
  app.use(
    "/api/jobs",
    createJobsRouter(
      mockSearchScraper as never,
      mockDetailScraper as never,
    ),
  );
  return app;
}

const sampleJobCard: JobCard = {
  id: "123",
  title: "Junior Java Developer",
  company: "Tech Corp",
  location: "São Paulo, Brazil",
  postedAt: "2024-01-15",
  summary: "Great opportunity for junior developers",
};

const sampleJobDetail: JobDetail = {
  ...sampleJobCard,
  description: "Full job description here",
  requirements: ["Java", "Spring Boot"],
};

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("GET /api/jobs", () => {
  beforeEach(() => {
    mockSearchScraper.search.mockReset();
    mockDetailScraper.getDetail.mockReset();
  });

  it("should return 200 with job cards when keywords are provided", async () => {
    mockSearchScraper.search.mockResolvedValue([sampleJobCard]);

    const res = await request(createApp()).get(
      "/api/jobs?keywords=java+junior",
    );

    expect(res.status).toBe(200);
    expect(res.body).toEqual({
      success: true,
      data: [sampleJobCard],
    });
    expect(mockSearchScraper.search).toHaveBeenCalledWith(
      "java junior",
      undefined,
    );
  });

  it("should pass location parameter when provided", async () => {
    mockSearchScraper.search.mockResolvedValue([sampleJobCard]);

    await request(createApp()).get(
      "/api/jobs?keywords=java&location=Brazil",
    );

    expect(mockSearchScraper.search).toHaveBeenCalledWith("java", "Brazil");
  });

  it("should return empty array when no jobs match", async () => {
    mockSearchScraper.search.mockResolvedValue([]);

    const res = await request(createApp()).get(
      "/api/jobs?keywords=nonexistent",
    );

    expect(res.status).toBe(200);
    expect(res.body).toEqual({
      success: true,
      data: [],
    });
  });

  it("should return 400 when keywords are missing", async () => {
    const res = await request(createApp()).get("/api/jobs");

    expect(res.status).toBe(400);
    expect(res.body).toEqual({
      success: false,
      error: { code: "VALIDATION_ERROR", message: "keywords is required" },
    });
    expect(mockSearchScraper.search).not.toHaveBeenCalled();
  });

  it("should return 400 when keywords is empty string", async () => {
    const res = await request(createApp()).get("/api/jobs?keywords=");

    expect(res.status).toBe(400);
    expect(res.body).toEqual({
      success: false,
      error: { code: "VALIDATION_ERROR", message: "keywords is required" },
    });
    expect(mockSearchScraper.search).not.toHaveBeenCalled();
  });

  // -----------------------------------------------------------------------
  // Error scenarios
  // -----------------------------------------------------------------------

  it("should return 503 when browser is not ready", async () => {
    mockSearchScraper.search.mockRejectedValue(
      new Error("Browser is not launched. Call launch() first."),
    );

    const res = await request(createApp()).get(
      "/api/jobs?keywords=java",
    );

    expect(res.status).toBe(503);
    expect(res.body).toEqual({
      success: false,
      error: {
        code: "SERVICE_UNAVAILABLE",
        message: "Browser is not ready",
      },
    });
  });

  it("should return 429 when bot challenge is detected", async () => {
    mockSearchScraper.search.mockRejectedValue(
      new Error("Bot challenge detected on LinkedIn"),
    );

    const res = await request(createApp()).get(
      "/api/jobs?keywords=java",
    );

    expect(res.status).toBe(429);
    expect(res.body).toEqual({
      success: false,
      error: {
        code: "RATE_LIMITED",
        message: expect.stringContaining("bot challenge"),
      },
    });
  });

  it("should return 504 on scraper timeout", async () => {
    mockSearchScraper.search.mockRejectedValue(
      new Error("Navigation timeout (30s) while loading LinkedIn search"),
    );

    const res = await request(createApp()).get(
      "/api/jobs?keywords=java",
    );

    expect(res.status).toBe(504);
    expect(res.body).toEqual({
      success: false,
      error: {
        code: "GATEWAY_TIMEOUT",
        message: "Scraper timed out",
      },
    });
  });

  it("should return 500 on unknown scraper error", async () => {
    mockSearchScraper.search.mockRejectedValue(
      new Error("Something unexpected happened"),
    );

    const res = await request(createApp()).get(
      "/api/jobs?keywords=java",
    );

    expect(res.status).toBe(500);
    expect(res.body).toEqual({
      success: false,
      error: {
        code: "INTERNAL_ERROR",
        message: "An unexpected error occurred",
      },
    });
  });
});

// ---------------------------------------------------------------------------
// GET /api/jobs/:jobId
// ---------------------------------------------------------------------------

describe("GET /api/jobs/:jobId", () => {
  beforeEach(() => {
    mockSearchScraper.search.mockReset();
    mockDetailScraper.getDetail.mockReset();
  });

  it("should return 200 with job detail for valid numeric ID", async () => {
    mockDetailScraper.getDetail.mockResolvedValue(sampleJobDetail);

    const res = await request(createApp()).get("/api/jobs/123");

    expect(res.status).toBe(200);
    expect(res.body).toEqual({
      success: true,
      data: sampleJobDetail,
    });
    expect(mockDetailScraper.getDetail).toHaveBeenCalledWith(123);
  });

  it("should return 404 when job is not found (login wall)", async () => {
    mockDetailScraper.getDetail.mockResolvedValue(null);

    const res = await request(createApp()).get("/api/jobs/999");

    expect(res.status).toBe(404);
    expect(res.body).toEqual({
      success: false,
      error: {
        code: "NOT_FOUND",
        message: "Job 999 not found (login wall detected)",
      },
    });
  });

  it("should return 400 for non-numeric ID", async () => {
    const res = await request(createApp()).get("/api/jobs/abc");

    expect(res.status).toBe(400);
    expect(res.body).toEqual({
      success: false,
      error: {
        code: "VALIDATION_ERROR",
        message: "jobId must be a numeric value",
      },
    });
    expect(mockDetailScraper.getDetail).not.toHaveBeenCalled();
  });

  it("should return 400 for alphanumeric ID", async () => {
    const res = await request(createApp()).get("/api/jobs/123abc");

    expect(res.status).toBe(400);
    expect(res.body).toEqual({
      success: false,
      error: {
        code: "VALIDATION_ERROR",
        message: "jobId must be a numeric value",
      },
    });
  });

  it("should return 503 when browser is not ready for detail", async () => {
    mockDetailScraper.getDetail.mockRejectedValue(
      new Error("Failed to launch Chromium: connection refused"),
    );

    const res = await request(createApp()).get("/api/jobs/123");

    expect(res.status).toBe(503);
    expect(res.body).toEqual({
      success: false,
      error: {
        code: "SERVICE_UNAVAILABLE",
        message: "Browser is not ready",
      },
    });
  });

  it("should return 504 on detail scraper timeout", async () => {
    mockDetailScraper.getDetail.mockRejectedValue(
      new Error("Navigation timeout (30000ms) while loading job detail"),
    );

    const res = await request(createApp()).get("/api/jobs/123");

    expect(res.status).toBe(504);
    expect(res.body).toEqual({
      success: false,
      error: {
        code: "GATEWAY_TIMEOUT",
        message: "Scraper timed out",
      },
    });
  });
});
