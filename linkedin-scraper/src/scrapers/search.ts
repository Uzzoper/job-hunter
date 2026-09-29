import { BrowserManager } from "../services/browser.js";
import { JobCard } from "../types.js";

/** How many scroll/load rounds to attempt before giving up on a results list. */
export const MAX_PAGINATION_ROUNDS = 6;

/**
 * How many consecutive rounds that add zero new cards are needed before the
 * pagination loop stops. A single "0 new" round is NOT enough: lazy-loaded
 * results can take a round to render, and stopping on the first empty round
 * is exactly the production defect that capped results early.
 */
export const CONSECUTIVE_EMPTY_ROUNDS_BEFORE_STOP = 2;

/**
 * Candidate selectors for LinkedIn's "show more" / "see more jobs" affordance.
 * Guest sessions render a button at the bottom of the results list (the exact
 * markup varies by layout/locale/AB test), so the first visible candidate wins.
 */
const SHOW_MORE_SELECTORS = [
  "button[aria-label*='See more jobs']",
  "button[aria-label*='Ver mais vagas']",
  "button[aria-label*='Show more']",
  "button[aria-label*='Mostrar mais']",
  "button.infinite-scroller__show-more-button",
  "button.jobs-search-results-list__show-more",
];

/** Search facets forwarded from the Java caller (blank = unconfigured). */
export interface SearchFacets {
  workType?: string;
  seniority?: string;
  timeRange?: string;
}

/**
 * LinkedIn's native time-range codes — VERIFIED LIVE on the guest search page
 * (spec linkedin-search-facets.md, Amendment A, 2026-09-28): f_TPR=r2592000
 * collapses the result set to postings dated within a month, r604800 within a
 * week, r86400 within a day. f_WT (work type) and f_E (seniority) returned
 * byte-identical result sets in every tested form (f_WT=2 / f_WT=2&f_WT=3 /
 * f_E=1 / f_E=2, with refresh=true, sortBy, both geoId forms, second keyword)
 * — the guest SSR page does NOT honor them — so those facets ship as
 * pass-through (forwarded but unmapped) with a log.warn, never as a guess.
 */
const VERIFIED_TIME_RANGE_CODES: Record<string, string> = {
  past_day: "r86400",
  past_week: "r604800",
  past_month: "r2592000",
};

/** Build the LinkedIn search URL, preserving repeated geoId parameters. */
export function buildSearchUrl(
  keywords: string,
  location?: string,
  geoIds: string[] = [],
  facets?: SearchFacets,
): string {
  const params = new URLSearchParams();
  params.set("keywords", keywords);
  if (location) {
    params.set("location", location);
  }
  for (const geoId of geoIds) {
    const trimmed = geoId.trim();
    if (trimmed.length > 0) {
      params.append("geoId", trimmed);
    }
  }
  if (facets?.timeRange) {
    const nativeCode = VERIFIED_TIME_RANGE_CODES[facets.timeRange];
    if (nativeCode) {
      params.append("f_TPR", nativeCode);
    } else {
      console.warn(
        `[search] timeRange="${facets.timeRange}" is not a verified native code; no f_TPR param added`
      );
    }
  }
  if (facets?.workType) {
    console.warn(
      `[search] workType="${facets.workType}" forwarded but NOT applied: no LinkedIn f_WT code verified on the guest SSR page (Amendment A 2026-09-28)`
    );
  }
  if (facets?.seniority) {
    console.warn(
      `[search] seniority="${facets.seniority}" forwarded but NOT applied: no LinkedIn f_E code verified on the guest SSR page (Amendment A 2026-09-28)`
    );
  }
  return `https://www.linkedin.com/jobs/search?${params.toString()}`;
}

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
   * @param geoIds - Optional LinkedIn geographic IDs forwarded as repeated query parameters
   * @param facets - Optional search facets (work-type/seniority/time-range) forwarded
   *                 to the LinkedIn URL; only live-verified native codes are applied
   * @returns Array of JobCard objects
   */
  async search(
    keywords: string,
    location?: string,
    geoIds: string[] = [],
    facets?: SearchFacets,
  ): Promise<JobCard[]> {
    const searchUrl = buildSearchUrl(keywords, location, geoIds, facets);
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
        `[search] keywords="${keywords}", location="${location ?? ""}", geoIds="${geoIds.join(",")}", facets="${JSON.stringify(facets ?? {})}", count=${jobCards.length}`
      );

      return jobCards;
    } catch (error) {
      throw error;
    } finally {
      if (!page.isClosed()) {
        await page.close();
      }
      if (!context.isClosed()) {
        await context.close();
      }
    }
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

    // Pagination: click "show more"/"see more jobs" when present, scroll the
    // results container to load more, and break only after N consecutive rounds
    // add zero new cards. NOTE ON THE PRODUCTION ~60-JOB CEILING: a guest
    // session stops loading new cards at roughly 60 results (LinkedIn's
    // guest-visibility cap / login wall), which is why production plateaus at
    // "found 60 cards, 0 new" regardless of how many rounds we attempt. That is
    // LinkedIn visibility, NOT a pagination bug; reaching more requires a
    // logged-in session (follow-up, not implemented here).
    let consecutiveEmptyRounds = 0;

    for (let i = 0; i < MAX_PAGINATION_ROUNDS; i++) {
      const clickedShowMore = await this.clickShowMoreIfPresent(page);
      await this.scrollResultsList(page);
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
        `[search] pagination round ${i + 1}/${MAX_PAGINATION_ROUNDS}` +
          `${clickedShowMore ? " (clicked show more)" : ""}: found ${newCards.length} cards, ${addedCount} new`
      );

      if (addedCount === 0) {
        consecutiveEmptyRounds++;
        if (consecutiveEmptyRounds >= CONSECUTIVE_EMPTY_ROUNDS_BEFORE_STOP) {
          this.logger.log(
            `[search] no new cards for ${CONSECUTIVE_EMPTY_ROUNDS_BEFORE_STOP} consecutive rounds, stopping pagination`
          );
          break;
        }
      } else {
        consecutiveEmptyRounds = 0;
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

  /** Scroll the results list to the bottom (falling back to a window scroll). */
  private async scrollResultsList(page: import("playwright").Page): Promise<void> {
    await page.evaluate(() => {
      const container =
        document.querySelector(".jobs-search-results-list") ||
        document.querySelector("main ul");
      if (container) {
        container.scrollTop = container.scrollHeight;
      }
      window.scrollTo(0, document.body.scrollHeight);
    });
  }

  /**
   * Click LinkedIn's "show more"/"see more jobs" button when one is visible.
   * @returns true when a show-more button was clicked
   */
  private async clickShowMoreIfPresent(page: import("playwright").Page): Promise<boolean> {
    for (const selector of SHOW_MORE_SELECTORS) {
      const locator = page.locator(selector).first();
      const visible = await locator.isVisible().catch(() => false);
      if (visible) {
        await locator.click().catch(() => {
          this.logger.warn(`[search] failed to click show-more button "${selector}"`);
        });
        // Give the lazy-loaded cards time to render before scrolling/scraping.
        await page.waitForTimeout(1500);
        return true;
      }
    }
    return false;
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
