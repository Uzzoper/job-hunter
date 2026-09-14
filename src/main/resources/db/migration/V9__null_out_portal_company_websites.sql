-- V9: Null out portal-suffixed company websites
-- GupyProvider stored the list-API careerPageUrl (always a Gupy-hosted portal page,
-- e.g. https://techco.gupy.io) as company_website. Portal URLs are never real corporate
-- sites: enrichment skips them, so 802/806 non-null Gupy websites were dead weight.
-- The Gupy list API has no real company-website field, so portal-suffixed rows map to
-- NULL. New ingestions drop portal URLs in GupyProvider.mapNode and at the JobNormalizer
-- choke point (both reuse domain/PortalDomains suffixes).
-- Real-site extraction from detail pages is a separate future spike (out of scope).

UPDATE jobs
SET company_website = NULL
WHERE company_website LIKE '%gupy.io%'
   OR company_website LIKE '%gupy.com.br%'
   OR company_website LIKE '%infojobs.com.br%';