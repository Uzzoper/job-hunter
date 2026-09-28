import { describe, expect, it, jest } from "@jest/globals";
import {
  buildSearchUrl,
  SearchScraper,
  MAX_PAGINATION_ROUNDS,
  CONSECUTIVE_EMPTY_ROUNDS_BEFORE_STOP,
} from "../../src/scrapers/search.js";
import type { JobCard } from "../../src/types.js";

// ---------------------------------------------------------------------------
// buildSearchUrl
// ---------------------------------------------------------------------------

describe("buildSearchUrl", () => {
  it("should append every geoId as a repeated LinkedIn query parameter", () => {
    const url = buildSearchUrl("java junior", "Brazil", [
      "106057199",
      "102927786",
    ]);

    expect(url).toBe(
      "https://www.linkedin.com/jobs/search?keywords=java+junior&location=Brazil&geoId=106057199&geoId=102927786",
    );
  });
});

// ---------------------------------------------------------------------------
// Pagination helpers
// ---------------------------------------------------------------------------

interface CardScrape {
  url: string;
  title: string;
  company: string;
  location: string;
  postedDate: string;
}

/** Build a card batch: {id: n} cards for the given numeric ids. */
function cards(ids: number[]): CardScrape[] {
  return ids.map((id) => ({
    url: `https://www.linkedin.com/jobs/view/${id}`,
    title: `Job ${id}`,
    company: "Co",
    location: "SP",
    postedDate: "",
  }));
}

/**
 * Build a fake BrowserManager + Page for SearchScraper.
 *
 * `page.evaluate` is stubbed: scroll calls (callback contains scrollTop/scrollTo)
 * return undefined and increment scrollCalls; every other evaluate call (the card
 * scrape) returns the next batch from `batches` (repeating the last one forever,
 * which is what an exhausted results list looks like).
 */
function createFakeBrowser(script: {
  batches: CardScrape[][];
  showMoreSelectors?: string[];
}) {
  const scrollCalls = { count: 0 };
  const clickedShowMoreSelectors: string[] = [];
  let batchIndex = 0;

  const showMoreSelectors = script.showMoreSelectors ?? [];
  const isShowMore = (selector: string) => showMoreSelectors.includes(selector);

  const makeLocator = (selector: string) => {
    const locator: Record<string, unknown> = {};
    Object.assign(locator, {
      isVisible: jest.fn(async () => isShowMore(selector)),
      count: jest.fn(async () => (isShowMore(selector) ? 1 : 0)),
      click: jest.fn(async () => {
        if (isShowMore(selector)) {
          clickedShowMoreSelectors.push(selector);
        }
      }),
      innerText: jest.fn(async () => ""),
      first: () => locator,
    });
    return locator;
  };

  const page = {
    setExtraHTTPHeaders: jest.fn(async () => undefined),
    goto: jest.fn(async () => undefined),
    title: jest.fn(async () => "LinkedIn"),
    locator: jest.fn((selector: string) => makeLocator(selector)),
    waitForSelector: jest.fn(async () => ({})),
    waitForTimeout: jest.fn(async () => undefined),
    evaluate: jest.fn(async (fn: () => unknown) => {
      const src = fn.toString();
      if (src.includes("scrollTop") || src.includes("scrollTo")) {
        scrollCalls.count++;
        return undefined;
      }
      const batch = script.batches[Math.min(batchIndex, script.batches.length - 1)] ?? [];
      batchIndex++;
      return batch;
    }),
    close: jest.fn(async () => undefined),
    isClosed: jest.fn(() => false),
  };

  const browserManager = {
    newContext: jest.fn(async () => ({
      newPage: jest.fn(async () => page),
      close: jest.fn(async () => undefined),
      isClosed: jest.fn(() => false),
    })),
  };

  return { browserManager, scrollCalls, clickedShowMoreSelectors };
}

async function runSearch(script: {
  batches: CardScrape[][];
  showMoreSelectors?: string[];
}): Promise<{ jobs: JobCard[]; harness: ReturnType<typeof createFakeBrowser> }> {
  const harness = createFakeBrowser(script);
  const scraper = new SearchScraper(harness.browserManager as never);
  const jobs = await scraper.search("junior", "Brazil", ["106057199"]);
  return { jobs, harness };
}

// ---------------------------------------------------------------------------
// SearchScraper pagination (production defects: max 3 scrolls, break on the
// FIRST zero-new round, no "show more"/"see more jobs" clicking)
// ---------------------------------------------------------------------------

describe("SearchScraper pagination", () => {
  it("should accumulate cards across multiple scroll rounds (multi-round accumulation)", async () => {
    // initial 1 card; round 1 adds a 2nd; round 2 adds a 3rd; then the list is
    // exhausted (2 consecutive zero-new rounds are needed to stop).
    const { jobs, harness } = await runSearch({
      batches: [
        cards([1]),
        cards([1, 2]),
        cards([1, 2, 3]),
        cards([1, 2, 3]),
        cards([1, 2, 3]),
      ],
    });

    expect(jobs).toHaveLength(3);
    expect(new Set(jobs.map((j) => j.id))).toEqual(new Set(["1", "2", "3"]));
    // rounds that added cards (2) + CONSECUTIVE_EMPTY_ROUNDS_BEFORE_STOP zero rounds
    expect(harness.scrollCalls.count).toBe(2 + CONSECUTIVE_EMPTY_ROUNDS_BEFORE_STOP);
  });

  it("should break only after N consecutive zero-new rounds, not on the first one", async () => {
    // single card from the start: the first zero-new round MUST NOT stop the loop.
    const { jobs, harness } = await runSearch({
      batches: [cards([1]), cards([1]), cards([1])],
    });

    expect(jobs).toHaveLength(1);
    expect(harness.scrollCalls.count).toBe(CONSECUTIVE_EMPTY_ROUNDS_BEFORE_STOP);
  });

  it("should click a visible 'show more' button instead of only scrolling", async () => {
    const showMoreSelector = "button.infinite-scroller__show-more-button";
    const { jobs, harness } = await runSearch({
      showMoreSelectors: [showMoreSelector],
      batches: [cards([1]), cards([1, 2]), cards([1, 2])],
    });

    expect(harness.clickedShowMoreSelectors).toContain(showMoreSelector);
    expect(jobs).toHaveLength(2);
  });

  it("should keep scrolling past the old hard cap of 3 rounds while cards keep arriving", async () => {
    // each round adds one more card, never zero-new => the loop must run for
    // MAX_PAGINATION_ROUNDS rounds (more than the previous hard-coded 3).
    const batches = Array.from({ length: MAX_PAGINATION_ROUNDS + 1 }, (_, i) =>
      cards(Array.from({ length: i + 1 }, (_, k) => k + 1)),
    );
    const { jobs, harness } = await runSearch({ batches });

    expect(harness.scrollCalls.count).toBe(MAX_PAGINATION_ROUNDS);
    expect(jobs).toHaveLength(MAX_PAGINATION_ROUNDS);
  });
});