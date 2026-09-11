#!/usr/bin/env python3
"""
Company Scraper tests — issue #36 extenders.

Plain unittest (pytest-compatible). Sample HTML fixtures inline.

Run:
    python3 scraper_test.py
    python3 -m pytest scraper_test.py
"""

import unittest
import json
import subprocess
import sys
import os

# Ensure the module under test is importable whether run from inside
# skills/company-scraper/ or from the repo root.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import scraper


# ---------------------------------------------------------------------------
# Sample HTML fixtures (inline)
# ---------------------------------------------------------------------------

CULTURE_HTML = """
<html><body>
  <p>Nossa cultura preza pelo trabalho em modelo remoto e home office,
  com foco em clean code e TDD. Oferecemos vale refeição e plano de saúde.</p>
</body></html>
"""

PRODUCTS_HTML = """
<html><body>
  <p>Somos uma plataforma de gestão financeira para pequenas empresas.</p>
</body></html>
"""

TEAM_SIZE_1_HTML = """
<html><body>
  <p>Contamos com 50-200 funcionários espalhados pelo Brasil.</p>
</body></html>
"""

TEAM_SIZE_2_HTML = """
<html><body>
  <p>Nosso time tem mais de 100 pessoas.</p>
</body></html>
"""

TEAM_SIZE_3_HTML = """
<html><body>
  <p>Somos um time de 25 pessoas.</p>
</body></html>
"""

FUNDING_HTML = """
<html><body>
  <p>Depois da rodada de Seed, captamos nossa Série A em 2025.</p>
</body></html>
"""

NEWS_HTML = """
<html><body>
  <p>Em 2025 realizamos uma nova captação de investimento.</p>
  <p>No início de 2026 anunciamos a aquisição da rival.</p>
</body></html>
"""

FULL_CORP_HTML = """
<html>
<head>
  <title>Acme Corp</title>
  <meta name="description" content="Acme Corp e uma empresa de tecnologia em Sao Paulo.">
</head>
<body>
  <p>Trabalhamos em regime remoto, valorizamos clean code, TDD e metodologias ágeis.
  Oferecemos vale alimentação e um excelente plano de saúde.</p>
  <p>Somos uma plataforma de pagamentos e soluções em fintech. Contamos com mais de 200 pessoas.</p>
  <p>Iniciamos bootstrapped e em 2025 fizemos nossa rodada Série B.</p>
</body>
</html>
"""


# ---------------------------------------------------------------------------
# Culture extraction
# ---------------------------------------------------------------------------

class ExtractCultureTests(unittest.TestCase):
    """extract_culture(text) -> comma-separated string or None."""

    def test_multiple_workplace_benefits(self):
        result = scraper.extract_culture(CULTURE_HTML)
        self.assertIsNotNone(result)
        for item in ("remoto", "home office", "clean code", "tdd",
                     "vale refeição", "plano de saúde"):
            self.assertIn(item, result.lower())

    def test_multiple_values_comma_separated(self):
        result = scraper.extract_culture(
            "Trabalho híbrido com vale alimentação."
        )
        self.assertIn("híbrido", result.lower())
        self.assertIn("vale alimentação", result.lower())
        self.assertEqual(result.count(","), 1)

    def test_single_value(self):
        result = scraper.extract_culture("Regime presencial.")
        self.assertTrue(result.lower().startswith("presencial"))

    def test_no_culture_returns_none(self):
        self.assertIsNone(scraper.extract_culture("Apenas um texto neutro."))

    def test_returns_string(self):
        result = scraper.extract_culture(CULTURE_HTML)
        self.assertIsInstance(result, str)


# ---------------------------------------------------------------------------
# Product extraction
# ---------------------------------------------------------------------------

class ExtractProductsTests(unittest.TestCase):
    """extract_products(text) -> first product match or None."""

    def test_plataforma_de(self):
        result = scraper.extract_products(PRODUCTS_HTML)
        self.assertIsNotNone(result)
        self.assertIn("plataforma", result.lower())

    def test_solucoes_em(self):
        result = scraper.extract_products("Nossas soluções em logística integram tudo.")
        self.assertIsNotNone(result)
        self.assertIn("soluções", result.lower())

    def test_oferecemos(self):
        result = scraper.extract_products("Oferecemos software de RH completo.")
        self.assertIsNotNone(result)

    def test_nossos_produtos(self):
        result = scraper.extract_products("Conheça nossos produtos para energia solar.")
        self.assertIsNotNone(result)

    def test_no_product_returns_none(self):
        self.assertIsNone(scraper.extract_products("Texto sem produto."))


# ---------------------------------------------------------------------------
# Team size extraction
# ---------------------------------------------------------------------------

class ExtractTeamSizeTests(unittest.TestCase):
    """extract_team_size(text) -> '50-200' style string or None."""

    def test_range_employees(self):
        self.assertEqual(scraper.extract_team_size(TEAM_SIZE_1_HTML), "50-200")

    def test_mais_de_100(self):
        self.assertEqual(scraper.extract_team_size(TEAM_SIZE_2_HTML), "100+")

    def test_time_de_25(self):
        self.assertEqual(scraper.extract_team_size(TEAM_SIZE_3_HTML), "25")

    def test_colaboradores_variant(self):
        self.assertEqual(
            scraper.extract_team_size("Temos 10-30 colaboradores no time."),
            "10-30",
        )

    def test_no_team_size_returns_none(self):
        self.assertIsNone(scraper.extract_team_size("Texto qualquer."))


# ---------------------------------------------------------------------------
# Funding extraction
# ---------------------------------------------------------------------------

class ExtractFundingTests(unittest.TestCase):
    """extract_funding(text) -> funding stage or None."""

    def test_seed(self):
        self.assertEqual(scraper.extract_funding("Rodada Seed concluída."), "Seed")

    def test_serie_a_portuguese(self):
        self.assertEqual(
            scraper.extract_funding("Captamos nossa Série A em 2025."),
            "Série A",
        )

    def test_serie_b_english(self):
        self.assertEqual(
            scraper.extract_funding("We closed a Series B round."),
            "Series B",
        )

    def test_serie_c(self):
        self.assertIn("C", scraper.extract_funding("Serie C round announced."))

    def test_bootstrapped(self):
        self.assertEqual(
            scraper.extract_funding("Somos uma empresa bootstrapped desde 2018."),
            "Bootstrapped",
        )

    def test_aporte(self):
        self.assertEqual(
            scraper.extract_funding("Recebemos um novo aporte em 2025."),
            "Aporte",
        )

    def test_captacao(self):
        self.assertIn("captação", scraper.extract_funding(
            "Nova captação de R$10M concluída."
        ).lower())

    def test_no_funding_returns_none(self):
        self.assertIsNone(scraper.extract_funding("Texto sem financiamento."))


# ---------------------------------------------------------------------------
# Recent news extraction
# ---------------------------------------------------------------------------

class ExtractRecentNewsTests(unittest.TestCase):
    """extract_recent_news(text) -> semicolon-joined snippets or None."""

    def test_captacao_year(self):
        result = scraper.extract_recent_news(NEWS_HTML)
        self.assertIsNotNone(result)
        self.assertIn("captação", result.lower())
        self.assertIn("2025", result)

    def test_aquisicao_year(self):
        result = scraper.extract_recent_news(NEWS_HTML)
        self.assertIn("aquisição", result.lower())
        self.assertIn("2026", result)

    def test_multiple_snippets_semicolon_joined(self):
        result = scraper.extract_recent_news(NEWS_HTML)
        self.assertIn(";", result)

    def test_lancamento(self):
        self.assertIn(
            "lançamento",
            scraper.extract_recent_news(
                "Em 2026 fizemos o lançamento de um novo produto."
            ).lower(),
        )

    def test_expansao(self):
        self.assertIn(
            "expansão",
            scraper.extract_recent_news(
                "2025 marcou nossa expansão internacional."
            ).lower(),
        )

    def test_no_recent_news_returns_none(self):
        self.assertIsNone(scraper.extract_recent_news("Texto antigo sem datas."))


# ---------------------------------------------------------------------------
# Output schema (scrape-level)
# ---------------------------------------------------------------------------

class ScrapeOutputSchemaTests(unittest.TestCase):
    """scrape() output includes all #36 fields."""

    def test_output_contains_new_fields(self):
        result = scraper.scrape_from_html(
            FULL_CORP_HTML,
            url="https://acme.com.br",
            company="Acme Corp",
        )
        for key in ("culture", "products", "teamSize", "funding",
                    "recentNews", "linkedinUrl", "contactEmail",
                    "careersUrl", "description", "signals"):
            self.assertIn(key, result)

    def test_linkedin_url_null_when_not_provided(self):
        result = scraper.scrape_from_html("", url="https://acme.com.br")
        self.assertIsNone(result["linkedinUrl"])


# ---------------------------------------------------------------------------
# CLI smoke test (--linkedin-url)
# ---------------------------------------------------------------------------

class CliSmokeTests(unittest.TestCase):
    """CLI can be invoked with --linkedin-url."""

    def test_usage_message_without_url(self):
        proc = subprocess.run(
            [sys.executable, scraper.__file__],
            capture_output=True, text=True,
        )
        self.assertIn("usage", proc.stdout.lower())

    def test_option_parser_no_mock(self):
        # Verify the option-parsing helper handles --linkedin-url.
        args = scraper.parse_args(["--url", "https://acme.com.br",
                                   "--linkedin-url", "https://www.linkedin.com/company/acme"])
        self.assertEqual(args["url"], "https://acme.com.br")
        self.assertEqual(
            args["linkedin_url"],
            "https://www.linkedin.com/company/acme",
        )
        self.assertIsNone(args["company"])


# ---------------------------------------------------------------------------
# STOP_WORDS filter
# ---------------------------------------------------------------------------

class StopWordsFilterTests(unittest.TestCase):
    """Generic short tokens must not surface as tech signals."""

    def test_go_not_treated_as_tech(self):
        result = scraper.scrape_from_html("<html><body><p>We go to work every day.</p></body></html>", url="https://example.com")
        if result.get("signals"):
            self.assertNotIn("go", [s.lower() for s in result["signals"]])

    def test_golang_still_detected(self):
        result = scraper.scrape_from_html("<html><body><p>Our stack includes Golang for backend services.</p></body></html>", url="https://example.com")
        self.assertIn("golang", [s.lower() for s in result.get("signals", [])])


# ---------------------------------------------------------------------------
# Word boundaries
# ---------------------------------------------------------------------------

class WordBoundaryTests(unittest.TestCase):
    """Substrings of longer tech names must not double-report."""

    def test_java_in_javascript_not_doubled(self):
        result = scraper.scrape_from_html("<html><body><p>We use JavaScript.</p></body></html>", url="https://example.com")
        signals_lower = [s.lower() for s in result.get("signals", [])]
        self.assertIn("javascript", signals_lower)
        self.assertNotIn("java", signals_lower)


# ---------------------------------------------------------------------------
# Web-search fallback
# ---------------------------------------------------------------------------

class GoogleFallbackTests(unittest.TestCase):
    """fetch() with a company hint always returns a string."""

    def test_fetch_fallback_returns_string(self):
        result = scraper.fetch("https://totally-fake-domain-12345.example", company="Acme Corp")
        self.assertIsInstance(result, str)


if __name__ == "__main__":
    unittest.main()
