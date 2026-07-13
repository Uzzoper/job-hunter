/**
 * DetailScraper extracts full job details from individual LinkedIn job pages.
 * Uses confirmed selectors from spike validation.
 */

import { BrowserManager } from "../services/browser.js";
import { JobDetail } from "../types.js";

const DETAIL_LOG_PREFIX = "[detail]";
const DETAIL_URL_BASE = "https://www.linkedin.com/jobs/view/";
const NAVIGATION_TIMEOUT_MS = 30_000;
const MIN_DELAY_MS = 500;
const MAX_DELAY_MS = 1000;

const LOGIN_WALL_KEYWORDS = ["sign in", "cadastre-se", "entrar", "sign up", "log in"];

/**
 * DetailScraper extracts full job description and metadata from individual LinkedIn job pages.
 */
export class DetailScraper {
  private readonly browserManager: BrowserManager;
  private readonly logger = console;

  constructor(browserManager: BrowserManager) {
    this.browserManager = browserManager;
  }

  /**
   * Fetch full job details from a LinkedIn job detail page.
   *
   * @param jobId - Numeric LinkedIn job ID (e.g., 4432484675)
   * @returns JobDetail object with full description and metadata, or null if login wall detected
   */
  async getDetail(jobId: number): Promise<JobDetail | null> {
    const url = `${DETAIL_URL_BASE}${jobId}`;

    // Human-like delay before navigation
    await this.randomDelay();

    const context = await this.browserManager.newContext();
    const page = await context.newPage();

    try {
      // Set realistic headers like search scraper
      await page.setExtraHTTPHeaders({
        "Accept-Language": "pt-BR,pt;q=0.9",
      });

      await this.navigateToDetail(page, url);
      await this.detectBotChallenge(page);

      const hasLoginWall = await this.detectLoginWall(page);
      if (hasLoginWall) {
        this.logger.log(`${DETAIL_LOG_PREFIX} jobId=${jobId}, hasLoginWall=true`);
        return null;
      }

      const detail = await this.extractDetail(page, jobId);

      this.logger.log(
        `${DETAIL_LOG_PREFIX} jobId=${jobId}, descriptionLength=${detail.description.length}, hasLoginWall=false`
      );

      return detail;
    } catch (error) {
      this.logger.error(`${DETAIL_LOG_PREFIX} jobId=${jobId}, error=${error instanceof Error ? error.message : String(error)}`);
      throw error;
    } finally {
      await page.close().catch(() => {});
      await context.close().catch(() => {});
    }
  }

  private async navigateToDetail(page: import("playwright").Page, url: string): Promise<void> {
    const startTime = Date.now();
    try {
      await page.goto(url, {
        waitUntil: "domcontentloaded",
        timeout: NAVIGATION_TIMEOUT_MS,
      });

      const navTime = Date.now() - startTime;

      // Wait for description content to render after DOM is available
      let descriptionFound = false;
      try {
        await page.waitForSelector(".description__text, .show-more-less-html", { timeout: 15000 });
        descriptionFound = true;
      } catch {
        descriptionFound = false;
      }

      // Also wait for criteria section if present (may load slightly later)
      let criteriaFound = false;
      try {
        await page.waitForSelector(".description__job-criteria-item", { timeout: 5000 });
        criteriaFound = true;
      } catch {
        criteriaFound = false;
      }

      this.logger.log(
        `${DETAIL_LOG_PREFIX} navigationTime=${navTime}ms, descriptionFound=${descriptionFound}, criteriaFound=${criteriaFound}`
      );
    } catch (error) {
      if (error instanceof Error && error.name === "TimeoutError") {
        throw new Error(`Navigation timeout (${NAVIGATION_TIMEOUT_MS}ms) while loading job detail: ${url}`);
      }
      throw new Error(`Failed to navigate to job detail: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  private async detectBotChallenge(page: import("playwright").Page): Promise<void> {
    const title = await page.title().catch(() => "");
    const bodyText = await page.locator("body").innerText().catch(() => "");

    const challengeKeywords = ["please verify", "are you a robot", "sorry", "verify you are human", "captcha"];
    const combinedText = `${title} ${bodyText}`.toLowerCase();

    for (const keyword of challengeKeywords) {
      if (combinedText.includes(keyword)) {
        throw new Error(`Bot challenge detected on LinkedIn job detail: "${keyword}" found in page`);
      }
    }
  }

  private async detectLoginWall(page: import("playwright").Page): Promise<boolean> {
    // Check if the main job content is actually blocked by a login wall
    // LinkedIn always has "Sign in" in header, so we check if job description is accessible
    const descriptionEl = await page
      .locator(".description__text, .show-more-less-html")
      .first()
      .isVisible()
      .catch(() => false);

    if (descriptionEl) {
      return false; // Job description is visible, no login wall blocking content
    }

    // Fallback: check if there's a modal/dialog blocking the content
    const modalVisible = await page
      .locator('[role="dialog"], .modal, .contextual-sign-in-modal')
      .first()
      .isVisible()
      .catch(() => false);

    if (modalVisible) {
      return true;
    }

    // Last resort: check body text but only if no job content found
    const bodyText = await page.locator("body").innerText().catch(() => "");
    const lowerText = bodyText.toLowerCase();

    for (const keyword of LOGIN_WALL_KEYWORDS) {
      if (lowerText.includes(keyword)) {
        return true;
      }
    }
    return false;
  }

  private async extractDetail(page: import("playwright").Page, jobId: number): Promise<JobDetail> {
    const detail = await page.evaluate(() => {
      // Description: try primary selector first, then fallback
      const descriptionEl =
        document.querySelector("div.description__text") ??
        document.querySelector("div.show-more-less-html");
      const description = descriptionEl?.innerHTML?.trim() ?? "";

      // Metadata: iterate over new .description__job-criteria-item list structure
      const criteriaMap: Record<string, string> = {
        // English labels
        "Seniority level": "seniority",
        "Employment type": "workType",
        "Job function": "jobFunction",
        "Industries": "industries",
        // Portuguese labels (LinkedIn serves localized content)
        "Nível de experiência": "seniority",
        "Tipo de emprego": "workType",
        "Função": "jobFunction",
        "Setores": "industries",
      };
      const criteriaItems = document.querySelectorAll(".description__job-criteria-item");
      let seniority = "";
      let workType = "";
      let jobFunction = "";
      let industries = "";

      // Collect raw criteria for debugging
      const rawCriteria: Array<{ label: string; value: string }> = [];

      if (criteriaItems.length === 0) {
        console.warn("[detail] No .description__job-criteria-item elements found — criteria section may be absent");
      } else {
        criteriaItems.forEach((item) => {
          const label =
            item.querySelector(".description__job-criteria-subheader")?.textContent?.trim() ?? "";
          const value =
            item.querySelector(".description__job-criteria-text")?.textContent?.trim() ?? "";
          rawCriteria.push({ label, value });
          if (label && value) {
            const field = criteriaMap[label];
            if (field === "seniority") seniority = value;
            else if (field === "workType") workType = value;
            else if (field === "jobFunction") jobFunction = value;
            else if (field === "industries") industries = value;
          }
        });
      }

      // Basic info from detail page for cross-reference
      const titleEl = document.querySelector("h1.top-card-layout__title, h1.job-title");
      const title = titleEl?.textContent?.trim() ?? "";

      const companyEl = document.querySelector("a.topcard__org-name-link, span.topcard__flavor");
      const company = companyEl?.textContent?.trim() ?? "";

      const locationEl = document.querySelector("span.topcard__flavor--bullet, span.job-search-card__location");
      const location = locationEl?.textContent?.trim() ?? "";

      const postedEl = document.querySelector("span.posted-time-ago__text, time");
      const postedAt = postedEl?.getAttribute("datetime") ?? postedEl?.textContent?.trim() ?? "";

return {
        description,
        seniority,
        workType,
        jobFunction,
        industries,
        title,
        company,
        location,
        postedAt,
        rawCriteria, // for debugging
      };
    });

    // Log raw criteria for debugging
    if (detail.rawCriteria && detail.rawCriteria.length > 0) {
      this.logger.log(`[detail] raw criteria: ${JSON.stringify(detail.rawCriteria)}`);
    } else {
      this.logger.warn(`[detail] No criteria items found in rawCriteria`);
    }

    // Parse requirements from description if present
    const requirements = this.extractRequirements(detail.description);

    return {
      id: String(jobId),
      title: detail.title,
      company: detail.company,
      location: detail.location,
      postedAt: detail.postedAt,
      summary: "",
      description: detail.description,
      requirements,
      salary: undefined,
      jobType: detail.workType || undefined,
      seniority: detail.seniority || undefined,
      jobFunction: detail.jobFunction || undefined,
      industries: detail.industries || undefined,
    };
  }

  /**
   * Extract requirements list from job description HTML.
   * Looks for common requirement sections (ul/ol lists after "Requirements", "Qualifications", etc.)
   */
  private extractRequirements(descriptionHtml: string): string[] {
    if (!descriptionHtml) {
      return [];
    }

    // Simple regex-based extraction for Node.js environment (no DOMParser)
    // Find list items in the description HTML
    const requirements: string[] = [];
    const requirementKeywords = ["requirements", "qualifications", "skills", "experience", "requisitos", "qualificações", "habilidades", "experiência"];

    // Simple regex to find <li>...</li> content
    const liRegex = /<li[^>]*>([\s\S]*?)<\/li>/gi;
    let match;
    while ((match = liRegex.exec(descriptionHtml)) !== null) {
      const text = match[1].replace(/<[^>]+>/g, "").trim();
      if (text && text.length > 5 && text.length < 200) {
        // Check if there's a requirement-like heading before this list
        // Look backwards from the match position for heading keywords
        const beforeText = descriptionHtml.substring(Math.max(0, match.index - 500), match.index).toLowerCase();
        if (requirementKeywords.some((kw) => beforeText.includes(kw))) {
          requirements.push(text);
        }
      }
    }

    // If no structured requirements found, return empty array
    // The AI analysis will extract requirements from the full description
    return requirements;
  }

  private async randomDelay(): Promise<void> {
    const delay = MIN_DELAY_MS + Math.random() * (MAX_DELAY_MS - MIN_DELAY_MS);
    await new Promise((resolve) => setTimeout(resolve, delay));
  }
}