import { describe, it, expect, jest, beforeEach } from "@jest/globals";
import express from "express";
import request from "supertest";
import { createHealthRouter } from "../../src/routes/health.js";

// ---------------------------------------------------------------------------
// Mock BrowserManager
// ---------------------------------------------------------------------------

const mockBrowserManager = {
  healthCheck: jest.fn<() => Promise<boolean>>(),
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function createApp() {
  const app = express();
  app.use("/health", createHealthRouter(mockBrowserManager as never));
  return app;
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("GET /health", () => {
  beforeEach(() => {
    mockBrowserManager.healthCheck.mockReset();
  });

  it("should return 200 with connected status when browser is healthy", async () => {
    mockBrowserManager.healthCheck.mockResolvedValue(true);

    const res = await request(createApp()).get("/health");

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

  it("should return 200 with disconnected status when browser fails health check", async () => {
    mockBrowserManager.healthCheck.mockResolvedValue(false);

    const res = await request(createApp()).get("/health");

    expect(res.status).toBe(200);
    expect(res.body).toEqual({
      success: true,
      data: {
        status: "ok",
        browser: "disconnected",
        uptime: expect.any(Number),
      },
    });
  });

  it("should include uptime as a non-negative integer", async () => {
    mockBrowserManager.healthCheck.mockResolvedValue(true);

    const res = await request(createApp()).get("/health");

    expect(res.body.data.uptime).toBeGreaterThanOrEqual(0);
    expect(Number.isInteger(res.body.data.uptime)).toBe(true);
  });
});
