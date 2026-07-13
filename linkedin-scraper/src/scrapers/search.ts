import { BrowserManager } from "../services/browser.js";
import { JobCard } from "../types.js";

/**
 * SearchScraper extracts job listings from LinkedIn search results pages.
 * Uses confirmed selectors from spike validation.
 */
export class SearchScraper {
  private readonly browserManager: BrowserManager;
  private readonly logger = console;

  constructor(browserManager: BrowserManager) {
    this.browserManager = browserManager;
  }

  /**
   * Search LinkedIn for jobs matching the given keywords and location.
   * @param keywords - Search keywords (e.g., "java junior")
   * @param location - Optional location (e.g., "Brazil")
   * @returns Array of JobCard objects
   */
  async search(keywords: string, location?: string): Promise<JobCard[]> {
    const searchUrl = this.buildSearchUrl(keywords, location);
    const context = await this.browserManager.newContext();
    const page = await context.newPage();

    try {
      await page.setExtraHTTPHeaders({
        "Accept-Language": "pt-BR,pt;q=0.9",
      });

      await this.navigateToSearch(page, searchUrl);
      await this.detectBotChallenge(page);
      await this.waitForJobCards(page);

      const jobCards = await this.extractJobCards(page);
      await this.randomDelay();

      this.logger.log(
        `[search] keywords="${keywords}", location="${location ?? ""}", count=${jobCards.length}`
      );

      return jobCards;
    } catch (error) {
      await page.close();
      await context.close();
      throw error;
    } finally {
      if (!page.isClosed()) {
        await page.close();
      }
      if (!context.browser()?.isConnected()) {
        await context.close();
      }
    }
  }

  private buildSearchUrl(keywords: string, location?: string): string {
    const params = new URLSearchParams();
    params.set("keywords", keywords);
    if (location) {
      params.set("location", location);
    }
    return `https://www.linkedin.com/jobs/search?${params.toString()}`;
  }

  private async navigateToSearch(page: import("playwright").Page, url: string): Promise<void> {
    try {
      await page.goto(url, {
        waitUntil: "domcontentloaded",
        timeout: 30_000,
      });
    } catch (error) {
      if (error instanceof Error && error.name === "TimeoutError") {
        throw new Error(`Navigation timeout (30s) while loading LinkedIn search: ${url}`);
      }
      throw new Error(`Failed to navigate to LinkedIn search: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  private async detectBotChallenge(page: import("playwright").Page): Promise<void> {
    const title = await page.title().catch(() => "");
    const bodyText = await page.locator("body").innerText().catch(() => "");

    const challengeKeywords = ["please verify", "are you a robot", "sorry", "verify you are human", "captcha"];
    const combinedText = `${title} ${bodyText}`.toLowerCase();

    for (const keyword of challengeKeywords) {
      if (combinedText.includes(keyword)) {
        throw new Error(`Bot challenge detected on LinkedIn: "${keyword}" found in page`);
      }
    }
  }

  private async waitForJobCards(page: import("playwright").Page): Promise<void> {
    try {
      await page.waitForSelector('a.base-card__full-link[href*="/jobs/view"]', {
        state: "attached",
        timeout: 15_000,
      });
    } catch (error) {
      if (error instanceof Error && error.name === "TimeoutError") {
        return;
      }
      throw error;
    }
  }

  private async extractJobCards(page: import("playwright").Page): Promise<JobCard[]> {
    const allCards = new Map<string, { url: string; title: string; company: string; location: string; postedDate: string }>();

    // First extraction
    const initialCards = await this.scrapeCurrentCards(page);
    initialCards.forEach((card) => allCards.set(card.url, card));
    this.logger.log(`[search] initial extraction: ${initialCards.length} cards`);

    // Pagination: scroll results container to load more, up to 3 additional scrolls
    for (let i = 0; i < 3; i++) {
      this.logger.log(`[search] pagination scroll ${i + 1}/3...`);

      await page.evaluate(() => {
        const container =
          document.querySelector(".jobs-search-results-list") ||
          document.querySelector("main ul");
        if (container) {
          container.scrollTop = container.scrollHeight;
        }
      });

      await page.waitForTimeout(3000);

      const newCards = await this.scrapeCurrentCards(page);
      let addedCount = 0;
      newCards.forEach((card) => {
        if (!allCards.has(card.url)) {
          allCards.set(card.url, card);
          addedCount++;
        }
      });

      this.logger.log(
        `[search] pagination scroll ${i + 1}/3: found ${newCards.length} cards, ${addedCount} new`
      );

      if (addedCount === 0) {
        this.logger.log("[search] no new cards after scroll, stopping pagination");
        break;
      }
    }

    return Array.from(allCards.values()).map((card) => ({
      id: this.extractJobId(card.url),
      title: card.title,
      company: card.company,
      location: card.location,
      postedAt: card.postedDate,
      summary: "",
    }));
  }

  /**
   * Scrape currently visible job cards from the page.
   * Queries data from the .base-card parent container (sibling of the anchor),
   * not from the anchor element itself.
   */
  private async scrapeCurrentCards(
    page: import("playwright").Page
  ): Promise<Array<{ url: string; title: string; company: string; location: string; postedDate: string }>> {
    return page.evaluate(() => {
      const anchorElements = Array.from(
        document.querySelectorAll('a.base-card__full-link[href*="/jobs/view"]')
      );

      return anchorElements.map((anchor) => {
        const parentCard = anchor.closest(".base-card, li");
        const url = anchor.getAttribute("href") ?? "";
        const title =
          parentCard
            ?.querySelector("h3.base-search-card__title")
            ?.textContent?.trim() ?? "";
        const company =
          parentCard
            ?.querySelector("h4.base-search-card__subtitle")
            ?.textContent?.trim() ?? "";
        const location =
          parentCard
            ?.querySelector("span.job-search-card__location")
            ?.textContent?.trim() ?? "";
        const postedDate =
          parentCard?.querySelector("time")?.getAttribute("datetime") ?? "";

        return { url, title, company, location, postedDate };
      });
    });
  }

  /**
   * Extract jobId from LinkedIn job URL.
   * URL format: https://br.linkedin.com/jobs/view/{slug}-{jobId}
   * Regex extracts the numeric jobId at the end of the path.
   */
  private extractJobId(url: string): string {
    const match = url.match(/(\d+)(?:\?|$)/);
    return match ? match[1] : url;
  }

  private async randomDelay(): Promise<void> {
    const delay = 1000 + Math.random() * 1000;
    await new Promise((resolve) => setTimeout(resolve, delay));
  }
}