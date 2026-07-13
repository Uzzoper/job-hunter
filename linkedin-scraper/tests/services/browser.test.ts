import { describe, it, expect, jest, beforeEach, afterEach } from "@jest/globals";
// NOTE: BrowserManager is imported dynamically below via getBmModule()
// to support jest.unstable_mockModule for ESM.

// ---------------------------------------------------------------------------
// Mock objects
// ---------------------------------------------------------------------------

const mockPage = {
  goto: jest.fn<() => Promise<void>>().mockResolvedValue(undefined),
  screenshot: jest.fn<() => Promise<Buffer>>().mockResolvedValue(Buffer.from("fake-png")),
  close: jest.fn<() => Promise<void>>().mockResolvedValue(undefined),
};

const mockContext = {
  newPage: jest.fn<() => Promise<typeof mockPage>>().mockResolvedValue(mockPage),
};

const mockBrowser = {
  newContext: jest.fn<() => Promise<typeof mockContext>>().mockResolvedValue(mockContext),
  newPage: jest.fn<() => Promise<typeof mockPage>>().mockResolvedValue(mockPage),
  close: jest.fn<() => Promise<void>>().mockResolvedValue(undefined),
  isConnected: jest.fn<() => boolean>().mockReturnValue(true),
  on: jest.fn<(event: string, handler: () => void) => void>(),
};

const mockLaunch = jest.fn<() => Promise<typeof mockBrowser>>().mockResolvedValue(mockBrowser);

jest.unstable_mockModule("playwright", () => ({
  chromium: {
    launch: mockLaunch,
  },
}));

// ---------------------------------------------------------------------------
// Dynamic module import helper
// ---------------------------------------------------------------------------

type BmModule = typeof import("../../src/services/browser.js");

async function getBmModule(): Promise<BmModule> {
  return import("../../src/services/browser.js");
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("BrowserManager", () => {
  let BrowserManager: BmModule["BrowserManager"];
  let BrowserError: BmModule["BrowserError"];
  let mgr: InstanceType<BmModule["BrowserManager"]>;

  beforeEach(async () => {
    const mod = await getBmModule();
    BrowserManager = mod.BrowserManager;
    BrowserError = mod.BrowserError;

    // Clear singleton before each test
    BrowserManager.resetInstance();
    mgr = BrowserManager.getInstance();

    // Reset all mock states
    mockPage.goto.mockReset().mockResolvedValue(undefined);
    mockPage.screenshot.mockReset().mockResolvedValue(Buffer.from("fake-png"));
    mockPage.close.mockReset().mockResolvedValue(undefined);
    mockContext.newPage.mockReset().mockResolvedValue(mockPage);
    mockBrowser.newContext.mockReset().mockResolvedValue(mockContext);
    mockBrowser.newPage.mockReset().mockResolvedValue(mockPage);
    mockBrowser.close.mockReset().mockResolvedValue(undefined);
    mockBrowser.isConnected.mockReset().mockReturnValue(true);
    mockBrowser.on.mockReset();
    mockLaunch.mockReset().mockResolvedValue(mockBrowser);
  });

  afterEach(async () => {
    await mgr.close().catch(() => {});
    BrowserManager.resetInstance();
  });

  // -----------------------------------------------------------------------
  // Singleton
  // -----------------------------------------------------------------------

  describe("getInstance", () => {
    it("should return the same instance on repeated calls", () => {
      const a = BrowserManager.getInstance();
      const b = BrowserManager.getInstance();
      expect(a).toBe(b);
    });

    it("should return a new instance after resetInstance", () => {
      const a = BrowserManager.getInstance();
      BrowserManager.resetInstance();
      const b = BrowserManager.getInstance();
      expect(a).not.toBe(b);
    });
  });

  // -----------------------------------------------------------------------
  // launch
  // -----------------------------------------------------------------------

  describe("launch", () => {
    it("should start Chromium with headless and required args", async () => {
      await mgr.launch();

      expect(mockLaunch).toHaveBeenCalledTimes(1);
      expect(mockLaunch).toHaveBeenCalledWith({
        headless: true,
        args: ["--no-sandbox", "--disable-setuid-sandbox"],
      });
    });

    it("should set isLaunched to true after success", async () => {
      expect(mgr.isLaunched()).toBe(false);
      await mgr.launch();
      expect(mgr.isLaunched()).toBe(true);
    });

    it("should be idempotent — second call does not re-launch", async () => {
      await mgr.launch();
      await mgr.launch();
      expect(mockLaunch).toHaveBeenCalledTimes(1);
    });

    it("should throw BrowserError when launch fails", async () => {
      mockLaunch.mockRejectedValue(new Error("Connection refused"));

      await expect(mgr.launch()).rejects.toThrow(BrowserError);
      await expect(mgr.launch()).rejects.toThrow("Failed to launch Chromium: Connection refused");

      expect(mgr.isLaunched()).toBe(false);
    });
  });

  // -----------------------------------------------------------------------
  // healthCheck
  // -----------------------------------------------------------------------

  describe("healthCheck", () => {
    it("should return true when browser is responsive", async () => {
      await mgr.launch();
      const healthy = await mgr.healthCheck();
      expect(healthy).toBe(true);
    });

    it("should return false when browser is not launched", async () => {
      const healthy = await mgr.healthCheck();
      expect(healthy).toBe(false);
    });

    it("should return false when screenshot fails", async () => {
      await mgr.launch();
      mockPage.screenshot.mockRejectedValueOnce(new Error("crash"));
      const healthy = await mgr.healthCheck();
      expect(healthy).toBe(false);
    });

    it("should return false when browser is null despite launched flag", async () => {
      await mgr.launch();
      // Simulate crash by nulling the browser reference via cast
      (mgr as unknown as { browser: null }).browser = null;
      const healthy = await mgr.healthCheck();
      expect(healthy).toBe(false);
    });
  });

  // -----------------------------------------------------------------------
  // isConnected
  // -----------------------------------------------------------------------

  describe("isConnected", () => {
    it("should return true when browser is connected", async () => {
      await mgr.launch();
      expect(mgr.isConnected()).toBe(true);
    });

    it("should return false when browser is not launched", () => {
      expect(mgr.isConnected()).toBe(false);
    });

    it("should return false when isConnected returns false", async () => {
      await mgr.launch();
      mockBrowser.isConnected.mockReturnValueOnce(false);
      expect(mgr.isConnected()).toBe(false);
    });
  });

  // -----------------------------------------------------------------------
  // newContext
  // -----------------------------------------------------------------------

  describe("newContext", () => {
    it("should create a context with pt-BR locale and realistic User-Agent", async () => {
      await mgr.launch();
      const ctx = await mgr.newContext();

      expect(mockBrowser.newContext).toHaveBeenCalledWith({
        userAgent: expect.stringContaining("Chrome"),
        locale: "pt-BR",
      });
      expect(ctx).toBe(mockContext);
    });

    it("should auto-launch browser when not launched", async () => {
      const ctx = await mgr.newContext();

      expect(mockLaunch).toHaveBeenCalledTimes(1);
      expect(ctx).toBe(mockContext);
    });

    it("should auto-restart on crash before creating context", async () => {
      await mgr.launch();
      // Simulate crash state
      (mgr as unknown as { _crashDetected: boolean })._crashDetected = true;
      (mgr as unknown as { browser: null }).browser = null;
      (mgr as unknown as { launched: boolean }).launched = false;

      await mgr.newContext();

      // Should have re-launched
      expect(mockLaunch).toHaveBeenCalledTimes(2);
    });
  });

  // -----------------------------------------------------------------------
  // close
  // -----------------------------------------------------------------------

  describe("close", () => {
    it("should close the browser gracefully", async () => {
      await mgr.launch();
      await mgr.close();

      expect(mockBrowser.close).toHaveBeenCalledTimes(1);
      expect(mgr.isLaunched()).toBe(false);
    });

    it("should be no-op when browser is not launched", async () => {
      await mgr.close();
      expect(mockBrowser.close).not.toHaveBeenCalled();
    });

    it("should not throw when browser.close fails", async () => {
      await mgr.launch();
      mockBrowser.close.mockRejectedValueOnce(new Error("shutdown error"));

      await expect(mgr.close()).resolves.toBeUndefined();
      expect(mgr.isLaunched()).toBe(false);
    });
  });

  // -----------------------------------------------------------------------
  // ensureBrowser
  // -----------------------------------------------------------------------

  describe("ensureBrowser", () => {
    it("should auto-launch browser when not launched", async () => {
      await mgr.ensureBrowser();

      expect(mockLaunch).toHaveBeenCalledTimes(1);
    });

    it("should restart when crash detected", async () => {
      await mgr.launch();
      (mgr as unknown as { _crashDetected: boolean })._crashDetected = true;
      (mgr as unknown as { browser: null }).browser = null;
      (mgr as unknown as { launched: boolean }).launched = false;

      await mgr.ensureBrowser();

      expect(mockLaunch).toHaveBeenCalledTimes(2);
    });

    it("should restart when browser is not connected", async () => {
      await mgr.launch();
      mockBrowser.isConnected.mockReturnValueOnce(false);

      await mgr.ensureBrowser();

      // First launch + re-launch on disconnect
      expect(mockLaunch).toHaveBeenCalledTimes(2);
    });
  });

  // -----------------------------------------------------------------------
  // isLaunched
  // -----------------------------------------------------------------------

  describe("isLaunched", () => {
    it("should return false initially", () => {
      expect(mgr.isLaunched()).toBe(false);
    });

    it("should return true after launch", async () => {
      await mgr.launch();
      expect(mgr.isLaunched()).toBe(true);
    });

    it("should return false after close", async () => {
      await mgr.launch();
      await mgr.close();
      expect(mgr.isLaunched()).toBe(false);
    });
  });
});
