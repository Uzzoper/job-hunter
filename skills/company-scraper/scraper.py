#!/usr/bin/env python3
"""
Company Scraper — Hermes Agent skill for Brazilian job listing enrichment.

Extracts structured company metadata from a given website:
- Contact emails (mailto links and inline text)
- Careers / hiring page links (Portuguese keywords)
- Company description (meta tags, Open Graph, first body paragraph)
- Technology signal keywords
- Culture signals (workplace regime, benefits) — issue #36
- Product description, team size, funding stage, recent news — issue #36
- Optional LinkedIn URL merge — issue #36

Dependencies: Python 3.8+ stdlib only (urllib, html.parser, re).
No external packages required.

Usage:
    python3 scraper.py <url>
    python3 scraper.py <url> --company "Company Name"
    python3 scraper.py <url> --linkedin-url https://www.linkedin.com/company/<x>

Output: JSON to stdout.
"""

import sys
import json
import re
import urllib.request
import urllib.error
import urllib.parse
from html.parser import HTMLParser
from typing import List, Optional, Dict, Any

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

DEFAULT_TIMEOUT = 10  # seconds
USER_AGENT = (
    "Mozilla/5.0 (compatible; JobHunterBot/1.0; "
    "+https://github.com/juanperuzzo/job-hunter)"
)

# Brazilian Portuguese keywords for careers / hiring page detection
CAREERS_KEYWORDS = [
    "trabalhe conosco",
    "trabalhe-conosco",
    "vagas",
    "carreiras",
    "recrutamento",
    "seleção",
    "oportunidades",
    "enfadeiras",   # rare but observed
    "considere-nos",
    "cultura",
]

# Email regex — matches standard email patterns
EMAIL_RE = re.compile(
    r"[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}",
    re.IGNORECASE,
)

# Portal domains to skip (mirrors PortalDomains.java)
PORTAL_DOMAINS = [
    "gupy.io",
    "gupy.com",
    "infojobs.com.br",
    "infojobs.com",
    "linkedin.com",
    "indeed.com",
    "indeed.com.br",
    "catho.com.br",
    "vagas.com.br",
]

# Tech keywords to scan for in page content
TECH_KEYWORDS = [
    # Languages
    "java", "python", "javascript", "typescript", "go", "golang", "rust",
    "c#", "c\\+\\+", "ruby", "php", "swift", "kotlin", "scala", "dart",
    # Frameworks
    "spring boot", "spring", "django", "flask", "fastapi", "rails",
    "react", "next\\.js", "nextjs", "vue", "nuxt", "angular", "svelte",
    "node\\.?js", "express", "nest\\.?js",
    "react native", "flutter",
    # Databases
    "postgresql", "postgres", "mysql", "mongodb", "redis", "elasticsearch",
    "sqlite", "oracle", "sql server",
    # Cloud / DevOps
    "aws", "azure", "gcp", "google cloud", "docker", "kubernetes", "k8s",
    "terraform", "jenkins", "github actions", "gitlab ci", "ci/cd",
    "linux",
    # Data
    "spark", "kafka", "airflow", "dbt",
]

# Compiled tech regex (case-insensitive)
_TECH_PATTERNS = [(kw, re.compile(kw, re.IGNORECASE)) for kw in TECH_KEYWORDS]

# ---------------------------------------------------------------------------
# #36: Culture / workplace / benefits keywords (Portuguese)
# ---------------------------------------------------------------------------

CULTURE_KEYWORDS = {
    "remoto": "remoto",
    "híbrido": "híbrido",
    "hibrido": "híbrido",
    "presencial": "presencial",
    "home office": "home office",
    "home-office": "home office",
    "clean code": "clean code",
    "clean-code": "clean code",
    "tdd": "tdd",
    "metodologias ágeis": "metodologias ágeis",
    "metodologias ageis": "metodologias ágeis",
    "vale refeição": "vale refeição",
    "vale-refeição": "vale refeição",
    "vale alimentação": "vale alimentação",
    "vale-alimentação": "vale alimentação",
    "plano de saúde": "plano de saúde",
    "plano de saude": "plano de saúde",
}

# Regex alternation built from culture keywords (compiled once)
_CULTURE_PATTERN = re.compile(
    "|".join(re.escape(k) for k in sorted(CULTURE_KEYWORDS, key=len, reverse=True)),
    re.IGNORECASE,
)

# ---------------------------------------------------------------------------
# #36: Product / team size / funding / recent-news patterns (Portuguese)
# ---------------------------------------------------------------------------

# Product-intro patterns: "plataforma de X", "soluções em X",
# "oferecemos X", "nossos produtos de/em X"
PRODUCT_RE = re.compile(
    r"(?i)(?:plataforma\s+de|solu[çc]õ[eo]s?\s+em|oferecemos|nossos\s+produtos?(?:\s+de|\s+em)?)\s+"
    r"([a-zà-ú0-9][a-zà-ú0-9\s&\-]{2,40}?)"
    r"(?=\s*[,.\.;\"'!?]|\s*<|\s*$|\s+e\s+[a-zA-Z])",
)

# Team size patterns:
#   - "50-200 funcionários/colaboradores/pessoas"
#   - "mais de 100 pessoas/funcionários/colaboradores"
#   - "time de N pessoas"
_TEAM_RANGE_RE = re.compile(
    r"(?i)(\d{1,3})\s*[-–]\s*(\d{1,3})\s*(?:funcion[áa]rios|colaboradores|pessoas|integrantes)",
)
_TEAM_MORE_RE = re.compile(r"(?i)mais\s+de\s+(\d{1,4})\s*(?:pessoas|funcion[áa]rios|colaboradores|integrantes)")
_TEAM_TIME_RE = re.compile(r"(?i)time\s+de\s+(\d{1,4})\s*(?:pessoas|pessoas\s*[,\.]?|funcion[áa]rios|colaboradores|integrantes)")

# Funding stage patterns (Portuguese + English variants)
FUNDING_PATTERNS = [
    (re.compile(r"(?i)\bseed\b"), "Seed"),
    (re.compile(r"(?i)s[ée]rie\s+a\b"), "Série A"),
    (re.compile(r"(?i)series?\s+a\b"), "Series A"),
    (re.compile(r"(?i)s[ée]rie\s+b\b"), "Série B"),
    (re.compile(r"(?i)series?\s+b\b"), "Series B"),
    (re.compile(r"(?i)s[ée]rie\s+c\b"), "Série C"),
    (re.compile(r"(?i)series?\s+c\b"), "Series C"),
    (re.compile(r"(?i)bootstrapped"), "Bootstrapped"),
    (re.compile(r"(?i)aporte"), "Aporte"),
    (re.compile(r"(?i)capta[çc][ãa]o"), "Captação"),
]

# Recent-news keywords + supported years
NEWS_KEYWORDS = ["captação", "captacao", "aquisição", "aquisicao",
                 "lançamento", "lancamento", "expansão", "expansao"]
NEWS_YEARS = ["2025", "2026"]
# Snippet window pattern (keyword surrounded by up to N chars of context)
_NEWS_PATTERN = re.compile(
    r"(?i)([^.]{{0,160}}?(?:{kw})[^.]{{0,200}}?\.?)".format(
        kw="|".join(NEWS_KEYWORDS)
    ),
)
# Year check handled separately to confirm recency

# Match a snippet to confirm it contains a supported recent year
_YEAR_PATTERN = re.compile(r"(?<!\d)(?:2025|2026)(?!\d)")


# ---------------------------------------------------------------------------
# Portal domain check
# ---------------------------------------------------------------------------

def _is_portal_domain(url: str) -> bool:
    """Return True if the URL belongs to a known job-portal domain."""
    try:
        host = urllib.parse.urlparse(url).hostname or ""
        host = host.lower()
        return any(host.endswith("." + pd) or host == pd for pd in PORTAL_DOMAINS)
    except Exception:
        return False


# ---------------------------------------------------------------------------
# HTML Parser
# ---------------------------------------------------------------------------

class CompanyPageParser(HTMLParser):
    """Lightweight HTML parser that extracts emails, links, descriptions,
    and tech signals without external dependencies."""

    def __init__(self, base_url: str):
        super().__init__()
        self.base_url = base_url
        self.emails: List[str] = []
        self.careers_links: List[str] = []
        self.description: Optional[str] = None
        self.title: Optional[str] = None
        self.signals: List[str] = []
        self._body_text_parts: List[str] = []
        self._in_body = False
        self._in_script = False
        self._in_style = False
        self._title_buf: List[str] = []
        self._in_title = False

    # -- Tag handlers ----------------------------------------------------------

    def handle_starttag(self, tag: str, attrs: List[tuple]):
        attrs_dict = dict(attrs)

        if tag == "title":
            self._in_title = True
            self._title_buf = []

        if tag == "body":
            self._in_body = True

        if tag in ("script", "noscript"):
            self._in_script = True
        if tag == "style":
            self._in_style = True

        # Extract emails from mailto: links
        if tag == "a":
            href = attrs_dict.get("href", "")
            if href.lower().startswith("mailto:"):
                email = href[7:].split("?")[0].strip()
                if EMAIL_RE.fullmatch(email):
                    self.emails.append(email)

            # Collect link text and href for careers detection
            link_text = ""  # populated in data handler via _current_link
            self._current_link_href = href
            self._current_link_text = ""

        # Meta description
        if tag == "meta":
            name = attrs_dict.get("name", "").lower()
            if name == "description":
                content = attrs_dict.get("content", "")
                if content and not self.description:
                    self.description = content.strip()[:500]

            # Open Graph description fallback
            prop = attrs_dict.get("property", "").lower()
            if prop == "og:description" and not self.description:
                content = attrs_dict.get("content", "")
                if content:
                    self.description = content.strip()[:500]

    def handle_endtag(self, tag: str):
        if tag == "title":
            self._in_title = False
            raw = "".join(self._title_buf).strip()
            if raw:
                self.title = raw[:200]

        if tag in ("script", "noscript"):
            self._in_script = False
        if tag == "style":
            self._in_style = False

        if tag == "a":
            href = getattr(self, "_current_link_href", "")
            link_text = getattr(self, "_current_link_text", "").lower()
            self._check_careers_link(href, link_text)

        if tag == "body":
            self._in_body = False

    def handle_data(self, data: str):
        if self._in_title:
            self._title_buf.append(data)
            return

        if self._in_script or self._in_style:
            return

        text = data.strip()
        if not text:
            return

        # Accumulate body text for tech-signal scanning
        if self._in_body:
            self._body_text_parts.append(text)

        # Track link text for careers detection
        if hasattr(self, "_current_link_text"):
            self._current_link_text += " " + text

        # Extract inline emails from text nodes
        for match in EMAIL_RE.findall(text):
            if match not in self.emails:
                self.emails.append(match)

        # Try to grab a description from first substantial paragraph
        if not self.description and self._in_body:
            if len(text) > 80:
                self.description = text[:500]

    def _check_careers_link(self, href: str, link_text: str):
        """Check if a link looks like a careers/hiring page."""
        if not href:
            return
        combined = link_text + " " + href.lower()
        for kw in CAREERS_KEYWORDS:
            if kw in combined:
                absolute = self._resolve_url(href)
                if absolute and absolute not in self.careers_links:
                    self.careers_links.append(absolute)
                break

    def _resolve_url(self, href: str) -> Optional[str]:
        """Resolve a possibly-relative URL against the base."""
        if not href or href.startswith(("javascript:", "mailto:", "#")):
            return None
        try:
            return urllib.parse.urljoin(self.base_url, href)
        except Exception:
            return None

    def extract_signals(self) -> List[str]:
        """Scan accumulated body text for technology keywords."""
        body = " ".join(self._body_text_parts)
        found: List[str] = []
        for keyword, pattern in _TECH_PATTERNS:
            if pattern.search(body) and keyword not in found:
                # Normalize display: unescape regex escapes for display
                display = keyword.replace("\\", "")
                found.append(display)
        return found


# ---------------------------------------------------------------------------
# Core functions
# ---------------------------------------------------------------------------

def fetch(url: str, timeout: int = DEFAULT_TIMEOUT) -> str:
    """Fetch a URL and return the response body as a string.

    Returns a JSON error string (not raises) on HTTP/connection errors
    so the caller can always parse JSON output.
    """
    if _is_portal_domain(url):
        return json.dumps({"error": "portal_domain_skipped", "url": url})

    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            # Read up to 2 MB to avoid huge pages
            raw = resp.read(2 * 1024 * 1024)
            content_type = resp.headers.get("Content-Type", "")
            # Determine encoding
            charset = "utf-8"
            if "charset=" in content_type:
                charset = content_type.split("charset=")[-1].strip()
            try:
                return raw.decode(charset, errors="replace")
            except (LookupError, UnicodeDecodeError):
                return raw.decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return json.dumps({
            "error": "access_denied" if e.code == 403 else "fetch_failed",
            "url": url,
            "status": e.code,
        })
    except (urllib.error.URLError, OSError, TimeoutError) as e:
        return json.dumps({"error": "fetch_failed", "url": url, "detail": str(e)})


def extract_emails(html: str) -> List[str]:
    """Extract email addresses from raw HTML.

    Returns a list of unique emails found in mailto links and inline text.
    """
    emails: List[str] = []

    # First, mailto links via regex (fast pass)
    for match in re.findall(r'mailto:([a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,})', html):
        if match not in emails:
            emails.append(match)

    # Then general email patterns in text (skip javascript URIs and image extensions)
    for match in EMAIL_RE.findall(html):
        # Filter out common false positives
        lower = match.lower()
        if lower.endswith((".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp")):
            continue
        if match not in emails:
            emails.append(match)

    return emails


def extract_careers_links(html: str, base_url: str) -> List[str]:
    """Extract careers / hiring page links from HTML.

    Looks for anchor tags whose text or href contains Brazilian Portuguese
    keywords related to job openings and careers.
    """
    links: List[str] = []
    seen = set()

    # Regex approach: find <a> tags and check text + href
    tag_re = re.compile(r'<a\s[^>]*href=["\']([^"\']+)["\'][^>]*>(.*?)</a>', re.IGNORECASE | re.DOTALL)
    for href, text in tag_re.findall(html):
        combined = (text + " " + href).lower()
        for kw in CAREERS_KEYWORDS:
            if kw in combined:
                try:
                    absolute = urllib.parse.urljoin(base_url, href)
                except Exception:
                    continue
                if absolute not in seen:
                    seen.add(absolute)
                    links.append(absolute)
                break

    return links


def extract_description(html: str) -> Optional[str]:
    """Extract a company description from HTML.

    Priority: meta description > og:description > first substantial <p>.
    """
    # Meta description
    meta_match = re.search(
        r'<meta\s+name=["\']description["\']\s+content=["\']([^"\']+)["\']',
        html, re.IGNORECASE,
    )
    if meta_match:
        desc = meta_match.group(1).strip()
        if len(desc) > 20:
            return desc[:500]

    # Reversed attribute order
    meta_match = re.search(
        r'<meta\s+content=["\']([^"\']+)["\']\s+name=["\']description["\']',
        html, re.IGNORECASE,
    )
    if meta_match:
        desc = meta_match.group(1).strip()
        if len(desc) > 20:
            return desc[:500]

    # Open Graph description
    og_match = re.search(
        r'<meta\s+(?:property|name)=["\']og:description["\']\s+content=["\']([^"\']+)["\']',
        html, re.IGNORECASE,
    )
    if og_match:
        desc = og_match.group(1).strip()
        if len(desc) > 20:
            return desc[:500]

    # First substantial <p> in body
    p_match = re.search(r'<p[^>]*>(.*?)</p>', html, re.IGNORECASE | re.DOTALL)
    if p_match:
        text = re.sub(r'<[^>]+>', '', p_match.group(1)).strip()
        if len(text) > 80:
            return text[:500]

    return None


def extract_culture(text: str) -> Optional[str]:
    """Extract workplace-culture / benefit signals from text.

    Scans for Portuguese keywords (remote/hybrid/on-site regime, clean code,
    TDD, agile methodologies, meal vouchers, health plan, etc.) and returns a
    comma-separated deduplicated string, or None when nothing matches.
    """
    if not text:
        return None
    found: List[str] = []
    for match in _CULTURE_PATTERN.findall(text):
        label = match.lower()
        canonical = CULTURE_KEYWORDS.get(label, label)
        if canonical not in found:
            found.append(canonical)
    return ", ".join(found) if found else None


def extract_products(text: str) -> Optional[str]:
    """Extract the first product/offering match from text.

    Matches Portuguese intro phrases like "plataforma de X",
    "soluções em X", "oferecemos X", "nossos produtos". Returns the product
    phrase (leading keyword included) or None.
    """
    if not text:
        return None
    match = PRODUCT_RE.search(text)
    if not match:
        return None
    # Return the full matched phrase (e.g. "plataforma de gestão financeira")
    return match.group(0).strip()


def extract_team_size(text: str) -> Optional[str]:
    """Extract a team-size range/count from text.

    Supports patterns: "50-200 funcionários", "mais de 100 pessoas",
    "time de 25 pessoas", and BR variants (colaboradores, integrantes).
    Returns a normalized string ("50-200", "100+", "25") or None.
    """
    if not text:
        return None

    # Range: "50-200 funcionários"
    m = _TEAM_RANGE_RE.search(text)
    if m:
        return f"{m.group(1)}-{m.group(2)}"

    # "mais de 100 pessoas" -> "100+"
    m = _TEAM_MORE_RE.search(text)
    if m:
        return f"{m.group(1)}+"

    # "time de 25 pessoas" -> "25"
    m = _TEAM_TIME_RE.search(text)
    if m:
        return m.group(1)

    return None


def extract_funding(text: str) -> Optional[str]:
    """Extract the funding stage from text.

    Matches Seed, Série/Series A/B/C (Portuguese + English),
    Bootstrapped, Aporte, Captação. Returns the first match or None.
    """
    if not text:
        return None
    for pattern, label in FUNDING_PATTERNS:
        if pattern.search(text):
            return label
    return None


def extract_recent_news(text: str) -> Optional[str]:
    """Extract recent-news snippets from text.

    Looks for Portuguese keywords (captação, aquisição, lançamento,
    expansão) near a recent year (2025/2026). Returns semicolon-joined
    snippets or None if none qualify.
    """
    if not text:
        return None

    snippets: List[str] = []
    seen = set()

    for keyword in NEWS_KEYWORDS:
        # Find each keyword occurrence and grab a window around it
        pattern = re.compile(
            r"(?i)[^.;!?]{{0,120}}?{kw}[^.;!?]{{0,140}}?".format(kw=re.escape(keyword))
        )
        for pm in pattern.finditer(text):
            snippet = pm.group(0).strip()
            if not _YEAR_PATTERN.search(snippet):
                continue  # not recent (no 2025/2026)
            normalized = snippet
            if normalized not in seen:
                seen.add(normalized)
                snippets.append(normalized)

    # Fallback: if the generic per-keyword scan found nothing but a paragraph
    # mentions a keyword close to a year, include a broader window.
    if not snippets:
        for pm in _NEWS_PATTERN.finditer(text):
            snippet = " ".join(pm.group(1).split()).strip()
            if _YEAR_PATTERN.search(snippet) and snippet not in seen:
                seen.add(snippet)
                snippets.append(snippet)

    return "; ".join(snippets) if snippets else None


def merge_short_from_linkedin(primary: Dict[str, Any], linkedin_html: str) -> Dict[str, Any]:
    """Merge lightweight fields from a LinkedIn company page HTML snippet.

    Does not override primary-URL fields; only fills gaps. Best-effort:
    LinkedIn pages returned via a non-logged fetch are often partial or
    blocked, so any parse failure is ignored.
    """
    if not linkedin_html:
        return primary

    # Company name from <title> if missing
    if not primary.get("company"):
        title = re.search(r'<title[^>]*>(.*?)</title>', linkedin_html, re.IGNORECASE | re.DOTALL)
        if title:
            candidate = re.sub(r"\s+", " ", title.group(1)).strip()[:200]
            candidate = re.sub(r"\s*\|\s*LinkedIn.*$", "", candidate, flags=re.IGNORECASE)
            if candidate:
                primary["company"] = candidate

    # Description from og:description if missing
    if not primary.get("description"):
        og = re.search(
            r'<meta\s+(?:property|name)=["\']og:description["\']\s+content=["\']([^"\']+)["\']',
            linkedin_html, re.IGNORECASE,
        )
        if og and len(og.group(1)) > 20:
            primary["description"] = og.group(1).strip()[:500]

    # Tech signals if empty (linkedin pages may list stack in "Specialties")
    if not primary.get("signals"):
        body_text = re.sub(r"<[^>]+>", " ", linkedin_html)
        parser = CompanyPageParser("")
        parser._body_text_parts = [body_text]
        try:
            primary["signals"] = parser.extract_signals()
        except Exception:
            pass

    return primary


def scrape_from_html(html: str, url: str, company: Optional[str] = None,
                     linkedin_html: str = "") -> Dict[str, Any]:
    """Scrape company metadata from already-fetched HTML (no network).

    Used by tests and by scrape() after fetching. Runs all extractors
    against the provided HTML/content and returns the full output schema.
    """
    emails = extract_emails(html)
    careers = extract_careers_links(html, url)
    description = extract_description(html)

    # Build parser for signals
    parser = CompanyPageParser(url)
    body_text = ""
    try:
        parser.feed(html)
        body_text = " ".join(parser._body_text_parts)
    except Exception:
        pass  # best-effort parsing

    signals = parser.extract_signals()

    result = {
        "company": company or parser.title,
        "website": url,
        "contactEmail": emails[0] if emails else None,
        "careersUrl": careers[0] if careers else None,
        "description": description,
        "signals": signals,
        "culture": extract_culture(body_text),
        "products": extract_products(body_text),
        "teamSize": extract_team_size(body_text),
        "funding": extract_funding(body_text),
        "recentNews": extract_recent_news(body_text),
        "linkedinUrl": None,
    }

    # Merge optional LinkedIn source
    if linkedin_html:
        result = merge_short_from_linkedin(result, linkedin_html)

    return result


def scrape(url: str, company: Optional[str] = None,
           linkedin_url: Optional[str] = None) -> Dict[str, Any]:
    """Main entry point: fetch a URL and extract all company metadata.

    Returns a dict matching the output JSON schema. On fetch errors,
    returns an error dict instead. An optional linkedin_url is fetched as a
    second source and merged in; if its fetch fails, the primary result is
    returned unchanged.
    """
    html = fetch(url)
    if not html or html.startswith('{"error"'):
        try:
            return json.loads(html)
        except Exception:
            return {"error": "empty_response", "url": url}

    linkedin_html = ""
    if linkedin_url:
        linkedin_html = fetch(linkedin_url)

    result = scrape_from_html(html, url=url, company=company,
                              linkedin_html=linkedin_html)
    result["linkedinUrl"] = linkedin_url
    return result


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def parse_args(argv: List[str]) -> Dict[str, Any]:
    """Parse command-line arguments into a dict.

    Supported flags:
        <url>                          required positional URL
        --company <name>               company name hint
        --linkedin-url <url>           optional LinkedIn company page URL
    """
    url = None
    company = None
    linkedin_url = None

    i = 0
    while i < len(argv):
        arg = argv[i]
        if arg == "--company":
            if i + 1 < len(argv):
                company = argv[i + 1]
                i += 2
                continue
        elif arg == "--linkedin-url":
            if i + 1 < len(argv):
                linkedin_url = argv[i + 1]
                i += 2
                continue
        elif not arg.startswith("--"):
            url = arg
        i += 1

    return {"url": url, "company": company, "linkedin_url": linkedin_url}


def main():
    """CLI entry point: python3 scraper.py <url> [--company <name>] [--linkedin-url <url>]"""
    args = parse_args(sys.argv[1:])

    if not args["url"]:
        print(json.dumps({
            "error": "usage",
            "detail": "python3 scraper.py <url> [--company <name>] [--linkedin-url <url>]",
        }))
        sys.exit(1)

    result = scrape(args["url"], company=args["company"],
                    linkedin_url=args["linkedin_url"])
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
