-- V10: Null out vaga-ja.com company websites
-- Live-verified: a fresh Gupy fetch stored company_website on vaga-ja.com (same JWT-path
-- shape as gupy.io careerPages, e.g. https://empresa.vaga-ja.com/eyJ...). Vaga Já is the
-- same job-board family as Gupy ("Vaga Já: portal voltado à Pessoa Candidata..."), with
-- pages carrying ?jobBoardSource=gupy_portal, so careerPageUrl legitimately points there.
-- Portal URLs are never real corporate sites; backfill the leaked rows to NULL. New
-- ingestions drop them via the shared domain/PortalDomains suffixes (GupyProvider +
-- JobNormalizer choke points).

UPDATE jobs
SET company_website = NULL
WHERE company_website LIKE '%vaga-ja.com%';