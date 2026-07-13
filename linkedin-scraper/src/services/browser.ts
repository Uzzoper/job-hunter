/**
 * BrowserManager — singleton Playwright browser lifecycle management.
 *
 * Responsibilities:
 *  - Lazy-initialize a Chromium instance on first {@link #launch()} call
 *  - Provide a light health check (screenshot of about:blank)
 *  - Create isolated contexts with realistic browser fingerprint
 *  - Graceful shutdown on SIGTERM / SIGINT
 *  - Auto-restart if the browser crashes mid-request
 *
 * Usage (lazy init):
 *   const mgr = BrowserManager.getInstance();
 *   await mgr.launch();
 *   const ctx = await mgr.newContext();
 *   // ... work with context ...
 *   await mgr.close();
 */

import { chromium, type Browser, type BrowserContext } from "playwright";

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const LOG_PREFIX = "[browser]";

const CHROMIUM_ARGS = ["--no-sandbox", "--disable-setuid-sandbox"];

/** A recent Chrome User-Agent (Chrome 129 on Linux x86_64) */
const DEFAULT_USER_AGENT =
  "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36";

// ---------------------------------------------------------------------------
// Custom error
// ---------------------------------------------------------------------------

export class BrowserError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "BrowserError";
  }
}

// ---------------------------------------------------------------------------
// Manager
// ---------------------------------------------------------------------------

export class BrowserManager {
  private static instance: BrowserManager | null = null;

  private browser: Browser | null = null;
  private launched = false;

  /** @internal exposed for testing only */
  /* private */ _crashDetected = false;

  // -----------------------------------------------------------------------
  // Singleton
  // -----------------------------------------------------------------------

  private constructor() {
    // no-op
  }

  /** Return the single BrowserManager instance (creates on first call). */
  static getInstance(): BrowserManager {
    if (!BrowserManager.instance) {
      BrowserManager.instance = new BrowserManager();
    }
    return BrowserManager.instance;
  }

  /** Reset the singleton (for testing). */
  static resetInstance(): void {
    BrowserManager.instance = null;
  }

  // -----------------------------------------------------------------------
  // Launch
  // -----------------------------------------------------------------------

  /**
   * Start a headless Chromium instance. Safe to call multiple times —
   * subsequent calls are no-ops if the browser is already running.
   *
   * @throws {BrowserError} if the browser fails to launch
   */
  async launch(): Promise<void> {
    if (this.browser && this.launched) {
      return;
    }

    console.log(`${LOG_PREFIX} launching Chromium headless`);

    try {
      this.browser = await chromium.launch({
        headless: true,
        args: CHROMIUM_ARGS,
      });

      this.launched = true;
      this._crashDetected = false;

      // Monitor for crashes — flag them so callers can trigger a restart
      this.browser.on("disconnected", () => {
        if (this.launched) {
          console.warn(`${LOG_PREFIX} browser disconnected (possible crash)`);
          this._crashDetected = true;
          this.launched = false;
          this.browser = null;
        }
      });

      console.log(`${LOG_PREFIX} browser launched`);
    } catch (cause) {
      this.browser = null;
      this.launched = false;
      throw new BrowserError(
        `Failed to launch Chromium: ${cause instanceof Error ? cause.message : String(cause)}`,
      );
    }
  }

  // -----------------------------------------------------------------------
  // Health check
  // -----------------------------------------------------------------------

  /**
   * Verify the browser is responsive by taking a screenshot of about:blank.
   *
   * @returns `true` if the browser works, `false` otherwise
   */
  async healthCheck(): Promise<boolean> {
    if (!this.browser || !this.launched) {
      return false;
    }

    try {
      const page = await this.browser.newPage();
      await page.goto("about:blank", { waitUntil: "domcontentloaded" });
      const buffer = await page.screenshot({ type: "png" });
      await page.close();

      return buffer !== null && buffer.length > 0;
    } catch {
      return false;
    }
  }

  /**
   * Perform a lightweight health check that does NOT open a new page.
   * Simply verifies that the browser object exists and is connected.
   */
  isConnected(): boolean {
    if (!this.browser || !this.launched) {
      return false;
    }
    return this.browser.isConnected();
  }

  // -----------------------------------------------------------------------
  // Context
  // -----------------------------------------------------------------------

  /**
   * Open a new browser context with a realistic User-Agent and pt-BR locale.
   *
   * @throws {BrowserError} if no browser is running
   */
  async newContext(): Promise<BrowserContext> {
    await this.ensureBrowser();

    const context = await this.browser!.newContext({
      userAgent: DEFAULT_USER_AGENT,
      locale: "pt-BR",
    });

    return context;
  }

  // -----------------------------------------------------------------------
  // Close
  // -----------------------------------------------------------------------

  /**
   * Gracefully shut down the browser.
   */
  async close(): Promise<void> {
    if (!this.browser || !this.launched) {
      return;
    }

    console.log(`${LOG_PREFIX} shutting down browser`);
    try {
      await this.browser.close();
    } catch (cause) {
      console.warn(`${LOG_PREFIX} error during browser close: ${cause instanceof Error ? cause.message : String(cause)}`);
    } finally {
      this.browser = null;
      this.launched = false;
      this._crashDetected = false;
      console.log(`${LOG_PREFIX} browser shut down`);
    }
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  /** @returns `true` if the browser has been launched (may have crashed since). */
  isLaunched(): boolean {
    return this.launched;
  }

  /**
   * Auto-restart the browser if a crash was detected.
   * Call this before each request to ensure the browser is alive.
   */
  async ensureBrowser(): Promise<void> {
    if (this._crashDetected) {
      console.warn(`${LOG_PREFIX} crash detected, restarting...`);
      await this.launch();
    }

    if (!this.browser || !this.launched) {
      console.log(`${LOG_PREFIX} browser not launched, auto-launching...`);
      await this.launch();
    }

    // At this point browser is guaranteed non-null (launch() throws on failure)
    if (!this.browser!.isConnected()) {
      console.warn(`${LOG_PREFIX} browser not connected, restarting...`);
      this.launched = false;
      this.browser = null;
      await this.launch();
    }
  }

  // -----------------------------------------------------------------------
  // Signal handlers (register once)
  // -----------------------------------------------------------------------

  /** Register SIGTERM / SIGINT handlers to gracefully close the browser. */
  registerSignalHandlers(): void {
    const shutdown = async (signal: string) => {
      console.log(`${LOG_PREFIX} received ${signal}, closing browser...`);
      await this.close();
    };

    process.on("SIGTERM", () => void shutdown("SIGTERM"));
    process.on("SIGINT", () => void shutdown("SIGINT"));
  }
}

// ---------------------------------------------------------------------------
// Convenience singleton export
// ---------------------------------------------------------------------------

/** Convenience alias for `BrowserManager.getInstance()`. */
export const browserManager = BrowserManager.getInstance();
