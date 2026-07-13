/**
 * Health router — handles /health endpoint.
 *
 * Reports overall service health and browser connectivity status.
 * Receives BrowserManager via dependency injection for testability.
 */

import { Router, type Request, type Response } from "express";
import type { ApiResponse } from "../types.js";
import { BrowserManager } from "../services/browser.js";

/**
 * Create an Express Router for the health check endpoint.
 *
 * @param browserManager  Instance of BrowserManager (or mock)
 */
export function createHealthRouter(browserManager: BrowserManager): Router {
  const router = Router();

  router.get("/", async (_req: Request, res: Response) => {
    const browserConnected = await browserManager.healthCheck();
    const uptime = Math.floor(process.uptime());

    const body: ApiResponse<{
      status: string;
      browser: string;
      uptime: number;
    }> = {
      success: true,
      data: {
        status: "ok",
        browser: browserConnected ? "connected" : "disconnected",
        uptime,
      },
    };

    res.json(body);
  });

  return router;
}
