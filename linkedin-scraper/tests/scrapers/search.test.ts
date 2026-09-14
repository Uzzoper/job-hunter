import { describe, expect, it } from "@jest/globals";
import { buildSearchUrl } from "../../src/scrapers/search.js";

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
