#!/usr/bin/env python3
"""
Company Scraper — Hermes Agent skill for Brazilian job listing enrichment.

Extracts structured company metadata from a given website:
- Contact emails (mailto links and inline text)
- Careers / hiring page links (Portuguese keywords)
- Company description (meta tags, Open Graph, first body paragraph)
- Technology signal keywords

Dependencies: Python 3.8+ stdlib only (urllib, html.parser, re).
No external packages required.

Usage:
    python3 scraper.py <url>
    python3 scraper.py <url> --company "Company Name"

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


def scrape(url: str, company: Optional[str] = None) -> Dict[str, Any]:
    """Main entry point: fetch a URL and extract all company metadata.

    Returns a dict matching the output JSON schema. On fetch errors,
    returns an error dict instead.
    """
    html = fetch(url)
    if not html or html.startswith('{"error"'):
        try:
            return json.loads(html)
        except Exception:
            return {"error": "empty_response", "url": url}

    emails = extract_emails(html)
    careers = extract_careers_links(html, url)
    description = extract_description(html)

    # Build parser for signals (reuse extraction logic)
    parser = CompanyPageParser(url)
    try:
        parser.feed(html)
    except Exception:
        pass  # best-effort parsing

    signals = parser.extract_signals()

    return {
        "company": company or parser.title,
        "website": url,
        "contactEmail": emails[0] if emails else None,
        "careersUrl": careers[0] if careers else None,
        "description": description,
        "signals": signals,
    }


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main():
    """CLI entry point: python3 scraper.py <url> [--company <name>]"""
    if len(sys.argv) < 2:
        print(json.dumps({"error": "usage", "detail": "python3 scraper.py <url> [--company <name>]"}))
        sys.exit(1)

    url = sys.argv[1]
    company = None

    if "--company" in sys.argv:
        idx = sys.argv.index("--company")
        if idx + 1 < len(sys.argv):
            company = sys.argv[idx + 1]

    result = scrape(url, company=company)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
