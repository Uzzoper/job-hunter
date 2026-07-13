import { describe, it, expect, jest } from "@jest/globals";
import request from "supertest";
import { createApp } from "../src/app.js";
import type { JobCard, JobDetail } from "../src/types.js";

// ---------------------------------------------------------------------------
// Mock dependencies
// ---------------------------------------------------------------------------

const mockSearchScraper = {
  search: jest.fn<(...args: string[]) => Promise<JobCard[]>>(),
};

const mockDetailScraper = {
  getDetail: jest.fn<(jobId: number) => Promise<JobDetail | null>>(),
};

const mockBrowserManager = {
  healthCheck: jest.fn<() => Promise<boolean>>(),
};

// ---------------------------------------------------------------------------
// App with mocks
// ---------------------------------------------------------------------------

const app = createApp({
  searchScraper: mockSearchScraper as never,
  detailScraper: mockDetailScraper as never,
  browserManager: mockBrowserManager as never,
});

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("GET /health", () => {
  it("should return 200 with status, browser, and uptime", async () => {
    mockBrowserManager.healthCheck.mockResolvedValue(true);

    const res = await request(app).get("/health");

    expect(res.status).toBe(200);
    expect(res.body).toEqual({
      success: true,
      data: {
        status: "ok",
        browser: "connected",
        uptime: expect.any(Number),
      },
    });
  });
});

describe("GET /api/jobs", () => {
  it("should return 200 with data array when keywords provided", async () => {
    mockSearchScraper.search.mockResolvedValue([]);

    const res = await request(app).get("/api/jobs?keywords=java");

    expect(res.status).toBe(200);
    expect(res.body).toEqual({
      success: true,
      data: [],
    });
  });

  it("should return 400 when keywords have only whitespace", async () => {
    const res = await request(app).get("/api/jobs?keywords=");

    expect(res.status).toBe(400);
    expect(res.body).toEqual({
      success: false,
      error: { code: "VALIDATION_ERROR", message: "keywords is required" },
    });
  });

  it("should return 400 when keywords are missing", async () => {
    const res = await request(app).get("/api/jobs");

    expect(res.status).toBe(400);
    expect(res.body).toEqual({
      success: false,
      error: { code: "VALIDATION_ERROR", message: "keywords is required" },
    });
  });
});

describe("GET /api/jobs/:jobId", () => {
  it("should return 200 with valid numeric ID", async () => {
    mockDetailScraper.getDetail.mockResolvedValue({
      id: "123",
      title: "Job Title",
      company: "Company",
      location: "Location",
      postedAt: "2024-01-01",
      summary: "",
      description: "",
      requirements: [],
    });

    const res = await request(app).get("/api/jobs/123");

    expect(res.status).toBe(200);
    expect(res.body.success).toBe(true);
    expect(res.body).toHaveProperty("data");
  });

  it("should return 400 with non-numeric ID", async () => {
    const res = await request(app).get("/api/jobs/abc");

    expect(res.status).toBe(400);
    expect(res.body).toEqual({
      success: false,
      error: { code: "VALIDATION_ERROR", message: "jobId must be a numeric value" },
    });
  });

  it("should return 400 with alphanumeric ID", async () => {
    const res = await request(app).get("/api/jobs/123abc");

    expect(res.status).toBe(400);
    expect(res.body).toEqual({
      success: false,
      error: { code: "VALIDATION_ERROR", message: "jobId must be a numeric value" },
    });
  });
});

describe("POST /api/jobs", () => {
  it("should return 405 method not allowed", async () => {
    const res = await request(app).post("/api/jobs");

    expect(res.status).toBe(405);
    expect(res.body).toEqual({
      success: false,
      error: { code: "METHOD_NOT_ALLOWED", message: "Method not allowed" },
    });
  });
});

describe("Unknown routes", () => {
  it("should return 404 for unknown route", async () => {
    const res = await request(app).get("/api/nonexistent");

    expect(res.status).toBe(404);
    expect(res.body).toEqual({
      success: false,
      error: { code: "NOT_FOUND", message: "Route not found" },
    });
  });
});
