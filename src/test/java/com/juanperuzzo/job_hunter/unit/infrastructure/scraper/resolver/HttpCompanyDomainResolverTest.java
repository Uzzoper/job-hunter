package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.resolver;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.juanperuzzo.job_hunter.infrastructure.scraper.ratelimit.TokenBucketRateLimiter;
import com.juanperuzzo.job_hunter.application.port.out.CompanyDomainResolverPort;
import com.juanperuzzo.job_hunter.infrastructure.scraper.resolver.HttpCompanyDomainResolver;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(WireMockExtension.class)
@DisplayName("HttpCompanyDomainResolver tests")
class HttpCompanyDomainResolverTest {

    private String baseUrl;
    private HttpCompanyDomainResolver resolver;
    private int jobSeq;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        baseUrl = wmRuntimeInfo.getHttpBaseUrl();
        jobSeq = 0;
        var retry = new ExponentialBackoffRetry(2, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofMillis(2));
        resolver = new HttpCompanyDomainResolver(
                RestClient.builder().baseUrl(baseUrl).build(),
                retry,
                new TokenBucketRateLimiter(100, 10, Map.of()));
    }

    @Test
    @DisplayName("resolve should fetch one detail page per host and key every job URL of that host")
    void resolve_whenSameHostAppearsMultipleTimes_shouldFetchDetailOnceAndKeyEveryUrl() {
        var page = "<html><body><a href=\"https://www.techco.com.br/\">Site</a></body></html>";
        stubFor(get(urlEqualTo("/gupy/techco/jobs/1")).willReturn(ok(page)));
        stubFor(get(urlEqualTo("/gupy/techco/jobs/2")).willReturn(ok(page)));
        stubFor(get(urlEqualTo("/gupy/techco/jobs/3")).willReturn(ok(page)));

        var urls = List.of(
                baseUrl + "/gupy/techco/jobs/1",
                baseUrl + "/gupy/techco/jobs/2",
                baseUrl + "/gupy/techco/jobs/3");

        var result = resolver.resolveCompanyWebsites(urls.stream().map(u -> new CompanyDomainResolverPort.JobRef(u, null, "gupy")).toList(), 100);

        assertEquals(3, result.size());
        urls.forEach(url -> assertEquals("https://www.techco.com.br", result.get(url),
                "every job URL of a resolved host must be keyed"));
        verify(1, getRequestedFor(urlEqualTo("/gupy/techco/jobs/1")));
        verify(0, getRequestedFor(urlEqualTo("/gupy/techco/jobs/2")));
        verify(0, getRequestedFor(urlEqualTo("/gupy/techco/jobs/3")));
    }

    @Test
    @DisplayName("resolve should not resolve any website when the page has only portal or social links")
    void resolve_whenDetailPageHasOnlyPortalOrSocialLinks_shouldReturnEmptyMap() {
        stubFor(get(urlEqualTo("/gupy/brand/jobs/1")).willReturn(ok("""
                <html><body>
                  <a href="https://brand.gupy.io/">Portal</a>
                  <a href="https://www.linkedin.com/company/brand">LinkedIn</a>
                  <a href="https://www.facebook.com/brand">Facebook</a>
                </body></html>
                """)));

        var result = resolver.resolveCompanyWebsites(List.of(new CompanyDomainResolverPort.JobRef(baseUrl + "/gupy/brand/jobs/1", null, "gupy")), 100);

        assertTrue(result.isEmpty(), "portal and social/tracker hosts must never be resolved");
    }

    @Test
    @DisplayName("resolve should skip a host whose detail page returns 404 instead of throwing")
    void resolve_whenDetailPageReturns404_shouldSkipHostWithoutThrowing() {
        stubFor(get(urlEqualTo("/gupy/ghost/jobs/1")).willReturn(aResponse().withStatus(404)));

        var result = resolver.resolveCompanyWebsites(List.of(new CompanyDomainResolverPort.JobRef(baseUrl + "/gupy/ghost/jobs/1", null, "gupy")), 100);

        assertTrue(result.isEmpty(), "a failed detail fetch must never fail the caller");
    }

    @Test
    @DisplayName("resolve should resolve at most maxHosts hosts and skip overflow hosts")
    void resolve_whenHostsExceedMaxHosts_shouldResolveOnlyUpToCap() {
        var port = baseUrl.substring(baseUrl.lastIndexOf(':') + 1);
        stubFor(get(urlEqualTo("/gupy/techco/jobs/1")).willReturn(ok(
                "<html><body><a href=\"https://www.techco.com.br/\">Site</a></body></html>")));
        stubFor(get(urlEqualTo("/gupy/dotnet/jobs/2")).willReturn(ok(
                "<html><body><a href=\"https://www.dotnet.com.br/\">Site</a></body></html>")));

        var techcoUrl = baseUrl + "/gupy/techco/jobs/1";
        var dotnetUrl = "http://127.0.0.1:" + port + "/gupy/dotnet/jobs/2";

        var result = resolver.resolveCompanyWebsites(List.of(new CompanyDomainResolverPort.JobRef(techcoUrl, null, "gupy"), new CompanyDomainResolverPort.JobRef(dotnetUrl, null, "gupy")), 1);

        assertEquals("https://www.techco.com.br", result.get(techcoUrl),
                "the first host in input order resolves deterministically under the cap");
        assertFalse(result.containsKey(dotnetUrl), "the overflow host must not be fetched");
        verify(1, getRequestedFor(urlEqualTo("/gupy/techco/jobs/1")));
        verify(0, getRequestedFor(urlEqualTo("/gupy/dotnet/jobs/2")));
    }

    @Test
    @DisplayName("resolve should reject a dotless host and pick the next eligible company link")
    void resolve_whenFirstLinkHostIsDotless_shouldSkipItAndPickEligibleCompanyLink() {
        stubFor(get(urlEqualTo("/gupy/techco/jobs/1")).willReturn(ok("""
                <html><body>
                  <a href="http://nodejs/en/docs">Node docs</a>
                  <a href="https://www.techco.com.br/">Site</a>
                </body></html>
                """)));

        var result = resolver.resolveCompanyWebsites(List.of(new CompanyDomainResolverPort.JobRef(baseUrl + "/gupy/techco/jobs/1", null, "gupy")), 100);

        assertEquals("https://www.techco.com.br", result.get(baseUrl + "/gupy/techco/jobs/1"),
                "the dotless host (nodejs) must be rejected, the company link must win");
    }

    @Test
    @DisplayName("resolve should strip gclid and utm query params before storing the website")
    void resolve_whenLinkHasTrackingParams_shouldStripGclidAndUtmBeforeStoring() {
        var port = baseUrl.substring(baseUrl.lastIndexOf(':') + 1);
        stubFor(get(urlEqualTo("/gupy/techco/jobs/1")).willReturn(ok(
                "<html><body><a href=\"https://www.techco.com.br/?utm_source=newsletter&gclid=abc&ref=x\">Site</a></body></html>")));
        stubFor(get(urlEqualTo("/gupy/dotnet/jobs/2")).willReturn(ok(
                "<html><body><a href=\"https://www.dotnet.com.br/?utm_campaign=devfair\">Site</a></body></html>")));

        var techcoUrl = baseUrl + "/gupy/techco/jobs/1";
        var dotnetUrl = "http://127.0.0.1:" + port + "/gupy/dotnet/jobs/2";

        var result = resolver.resolveCompanyWebsites(List.of(new CompanyDomainResolverPort.JobRef(techcoUrl, null, "gupy"), new CompanyDomainResolverPort.JobRef(dotnetUrl, null, "gupy")), 100);

        assertEquals("https://www.techco.com.br/?ref=x", result.get(techcoUrl),
                "gclid and utm params must be stripped, non-tracking params kept");
        assertEquals("https://www.dotnet.com.br", result.get(dotnetUrl),
                "a tracking-only query must not leave a dangling trailing slash");
    }

    @Test
    @DisplayName("resolve should return an empty map for empty input or a non-positive cap")
    void resolve_whenEmptyListOrNonPositiveCap_shouldReturnEmptyMap() {
        assertTrue(resolver.resolveCompanyWebsites(List.of(), 100).isEmpty());
        assertTrue(resolver.resolveCompanyWebsites(List.of(new CompanyDomainResolverPort.JobRef(baseUrl + "/gupy/techco/jobs/1", null, "gupy")), 0).isEmpty());
    }

    @Test
    @DisplayName("resolve should reject a node.js docs link and pick the next eligible company link")
    void resolve_whenLinkHostIsNodeJsToken_shouldRejectTechTokenHost() {
        assertLinkRejectedAndFallbackUsed("https://node.js/en/learn");
    }

    @Test
    @DisplayName("resolve should reject a react.js link and pick the next eligible company link")
    void resolve_whenLinkHostIsReactJsToken_shouldRejectTechTokenHost() {
        assertLinkRejectedAndFallbackUsed("https://react.js/docs");
    }

    @Test
    @DisplayName("resolve should reject an angular.js link and pick the next eligible company link")
    void resolve_whenLinkHostIsAngularJsToken_shouldRejectTechTokenHost() {
        assertLinkRejectedAndFallbackUsed("https://angular.js/guide");
    }

    @Test
    @DisplayName("resolve should reject a vue.js link and pick the next eligible company link")
    void resolve_whenLinkHostIsVueJsToken_shouldRejectTechTokenHost() {
        assertLinkRejectedAndFallbackUsed("https://vue.js/guide");
    }

    @Test
    @DisplayName("resolve should reject a next.js link and pick the next eligible company link")
    void resolve_whenLinkHostIsNextJsToken_shouldRejectTechTokenHost() {
        assertLinkRejectedAndFallbackUsed("https://next.js/docs");
    }

    @Test
    @DisplayName("resolve should reject a watson.data link and pick the next eligible company link")
    void resolve_whenLinkHostIsWatsonDataToken_shouldRejectTechTokenHost() {
        assertLinkRejectedAndFallbackUsed("https://watson.data/docs");
    }

    @Test
    @DisplayName("resolve should reject a subdomain of a denylisted tech-token host")
    void resolve_whenLinkHostIsSubdomainOfTechToken_shouldRejectTechTokenHost() {
        assertLinkRejectedAndFallbackUsed("https://docs.node.js/learn");
    }

    @Test
    @DisplayName("resolve should reject a host whose TLD is a single character")
    void resolve_whenLinkTldIsSingleChar_shouldRejectHost() {
        assertLinkRejectedAndFallbackUsed("https://empresa.c/");
    }

    @Test
    @DisplayName("resolve should reject a host whose TLD is not alphabetic")
    void resolve_whenLinkTldIsNotAlphabetic_shouldRejectHost() {
        assertLinkRejectedAndFallbackUsed("https://empresa.123/");
    }

    @Test
    @DisplayName("resolve should reject an IP-address host")
    void resolve_whenLinkHostIsIpAddress_shouldRejectHost() {
        assertLinkRejectedAndFallbackUsed("https://192.168.0.1/");
    }

    @Test
    @DisplayName("resolve should keep a legitimate Brazilian company host with an alphabetic TLD")
    void resolve_whenLinkHostIsLegitCompanyDomain_shouldKeepIt() {
        assertLinkKept("https://empresa.com.br/", "https://empresa.com.br");
    }

    @Test
    @DisplayName("resolve should keep lookalike hosts that merely end with a denylisted token")
    void resolve_whenLinkHostIsLookalikeOfTechToken_shouldKeepIt() {
        assertLinkKept("https://next.com.br/", "https://next.com.br");
        assertLinkKept("https://datanet.com.br/", "https://datanet.com.br");
        assertLinkKept("https://nodejs.com.br/", "https://nodejs.com.br");
    }

    @Test
    @DisplayName("resolve should reject every file/tech-extension TLD from the denylist")
    void resolve_whenLinkTldIsFileOrTechExtension_shouldRejectEveryDenylistedTld() {
        for (var tld : List.of("js", "java", "ts", "py", "json", "xml", "css", "html",
                "php", "sh", "yaml", "pdf")) {
            assertLinkRejectedAndFallbackUsed("https://empresa." + tld + "/pagina");
        }
    }

    @Test
    @DisplayName("resolve should reject a mixed-case gera.Java link and pick the next eligible company link")
    void resolve_whenLinkHostIsMixedCaseJavaExtension_shouldRejectHost() {
        assertLinkRejectedAndFallbackUsed("https://gera.Java/aplicacao");
    }

    @Test
    @DisplayName("resolve should keep legitimate company hosts on real TLDs that look like file extensions")
    void resolve_whenLinkTldIsRealTldLookingLikeExtension_shouldKeepHost() {
        assertLinkKept("https://empresa.net/", "https://empresa.net");
        assertLinkKept("https://blog.empresa.net/", "https://blog.empresa.net");
        assertLinkKept("https://empresa.md/", "https://empresa.md");
        assertLinkKept("https://empresa.zip/", "https://empresa.zip");
        assertLinkKept("https://empresa.cs/", "https://empresa.cs");
    }

    @Test
    @DisplayName("resolve should reject an ASP.NET link and pick the next eligible company link")
    void resolve_whenLinkHostIsAspNet_shouldRejectHost() {
        assertLinkRejectedAndFallbackUsed("https://asp.net/docs");
    }

    @Test
    @DisplayName("resolve should reject a subdomain of asp.net and pick the next eligible company link")
    void resolve_whenLinkHostIsSubdomainOfAspNet_shouldRejectHost() {
        assertLinkRejectedAndFallbackUsed("https://learn.asp.net/docs");
    }

    @Test
    @DisplayName("resolve should reject a bit.ly short link and pick the next eligible company link")
    void resolve_whenLinkHostIsBitLy_shouldRejectShortenerHost() {
        assertLinkRejectedAndFallbackUsed("https://bit.ly/xyz");
    }

    @Test
    @DisplayName("resolve should reject every shortener host, subdomains included")
    void resolve_whenLinkHostIsShortenerOrItsSubdomain_shouldRejectShortenerHost() {
        for (var host : List.of("tinyurl.com", "t.co", "goo.gl", "youtu.be", "is.gd",
                "cutt.ly", "abc.bit.ly")) {
            assertLinkRejectedAndFallbackUsed("https://" + host + "/xyz");
        }
    }

    @Test
    @DisplayName("resolve should reject myvue.js: a token lookalike still carries a file-extension TLD")
    void resolve_whenLinkHostIsTokenLookalikeWithExtensionTld_shouldRejectHost() {
        assertLinkRejectedAndFallbackUsed("https://myvue.js/");
    }

    @Test
    @DisplayName("resolve should keep legit company hosts on ccTLDs with tech-ish subdomains")
    void resolve_whenLinkHostIsTechLookingButLegit_shouldKeepIt() {
        assertLinkKept("https://dashboard.techco.io/", "https://dashboard.techco.io");
        assertLinkKept("https://dev.empresa.com.br/", "https://dev.empresa.com.br");
        assertLinkKept("https://empresa.ai/", "https://empresa.ai");
    }

    @Test
    @DisplayName("resolve should strip a gad param before storing the website")
    void resolve_whenLinkHasGadParam_shouldStripItBeforeStoring() {
        assertTrackingStripped("gad=xyz");
    }

    @Test
    @DisplayName("resolve should strip a fbclid param before storing the website")
    void resolve_whenLinkHasFbclidParam_shouldStripItBeforeStoring() {
        assertTrackingStripped("fbclid=xyz");
    }

    @Test
    @DisplayName("resolve should strip an msclkid param before storing the website")
    void resolve_whenLinkHasMsclkidParam_shouldStripItBeforeStoring() {
        assertTrackingStripped("msclkid=xyz");
    }

    @Test
    @DisplayName("resolve should strip utm params before storing the website")
    void resolve_whenLinkHasUtmParams_shouldStripThemBeforeStoring() {
        assertTrackingStripped("utm_source=news&utm_medium=email");
    }

    @Test
    @DisplayName("resolve should strip tracking params case-insensitively")
    void resolve_whenTrackingParamIsUpperCase_shouldStripItBeforeStoring() {
        assertTrackingStripped("GCLID=xyz&UTM_Campaign=devfair");
    }

    @Test
    @DisplayName("resolve should preserve non-tracking params and their order when stripping")
    void resolve_whenLinkMixesTrackingAndOtherParams_shouldKeepOthersInOrder() {
        assertLinkKept("https://www.techco.com.br/contato?id=7&gclid=x&ref=y&fbclid=z&pagina=2",
                "https://www.techco.com.br/contato?id=7&ref=y&pagina=2");
    }

    /**
     * Stubs a detail page whose only company link carries {@code trackingQuery} and
     * asserts the stored website drops it entirely.
     */
    private void assertTrackingStripped(String trackingQuery) {
        assertLinkKept("https://www.techco.com.br/ofertas?" + trackingQuery,
                "https://www.techco.com.br/ofertas");
    }

    /**
     * Stubs a detail page carrying {@code rejectedHref} followed by a legitimate
     * company link, then asserts the rejected host never becomes the stored
     * website and the fallback link wins.
     */
    private void assertLinkRejectedAndFallbackUsed(String rejectedHref) {
        var path = nextJobPath();
        stubFor(get(urlEqualTo(path)).willReturn(ok("<html><body>"
                + "<a href=\"" + rejectedHref + "\">Docs</a>"
                + "<a href=\"https://www.techco.com.br/\">Site</a>"
                + "</body></html>")));

        var jobUrl = baseUrl + path;
        var result = resolver.resolveCompanyWebsites(List.of(new CompanyDomainResolverPort.JobRef(jobUrl, null, "gupy")), 100);

        assertEquals("https://www.techco.com.br", result.get(jobUrl),
                "host must be rejected and the next eligible link must win: " + rejectedHref);
    }

    /** Stubs a detail page carrying a single company link and asserts it is stored. */
    private void assertLinkKept(String href, String expected) {
        var path = nextJobPath();
        stubFor(get(urlEqualTo(path)).willReturn(ok(
                "<html><body><a href=\"" + href + "\">Site</a></body></html>")));

        var jobUrl = baseUrl + path;
        var result = resolver.resolveCompanyWebsites(List.of(new CompanyDomainResolverPort.JobRef(jobUrl, null, "gupy")), 100);

        assertEquals(expected, result.get(jobUrl), "legitimate host must be kept: " + href);
    }

    @Test
    @DisplayName("resolve (greenhouse): two boards -> one fetch per board, each domain on its own job")
    void resolve_greenhouseTwoBoards_oneFetchPerBoard() {
        var pageA = "<html><body><a href=\"https://acme.com/\">Site</a></body></html>";
        var pageB = "<html><body><a href=\"https://globex.com/\">Site</a></body></html>";
        stubFor(get(urlEqualTo("/boarda/jobs/1")).willReturn(ok(pageA)));
        stubFor(get(urlEqualTo("/boardb/jobs/1")).willReturn(ok(pageB)));

        var urlA = baseUrl + "/boarda/jobs/1";
        var urlB = baseUrl + "/boardb/jobs/1";
        var result = resolver.resolveCompanyWebsites(List.of(
                new CompanyDomainResolverPort.JobRef(urlA, "Acme", "greenhouse"),
                new CompanyDomainResolverPort.JobRef(urlB, "Globex", "greenhouse")), 100);

        assertEquals("https://acme.com", result.get(urlA));
        assertEquals("https://globex.com", result.get(urlB));
        verify(1, getRequestedFor(urlEqualTo("/boarda/jobs/1")));
        verify(1, getRequestedFor(urlEqualTo("/boardb/jobs/1")));
    }

    @Test
    @DisplayName("resolve (greenhouse): company name matches one candidate -> picks by name (not first link)")
    void resolve_greenhouseCompanyMatchesOneCandidate_picksByName() {
        var page = "<html><body>"
                + "<a href=\"https://ubuntu.com/\">Ubuntu</a>"
                + "<a href=\"https://canonical.com/\">Canonical</a>"
                + "</body></html>";
        stubFor(get(urlEqualTo("/canonical/jobs/1")).willReturn(ok(page)));

        var url = baseUrl + "/canonical/jobs/1";
        var result = resolver.resolveCompanyWebsites(List.of(
                new CompanyDomainResolverPort.JobRef(url, "Canonical", "greenhouse")), 100);

        assertEquals("https://canonical.com", result.get(url),
                "name affinity must pick canonical.com over ubuntu.com");
    }

    @Test
    @DisplayName("resolve (greenhouse): no token overlap -> no metadata (never first link)")
    void resolve_greenhouseNoTokenOverlap_noMetadata() {
        var page = "<html><body><a href=\"https://ubuntu.com/\">Ubuntu</a></body></html>";
        stubFor(get(urlEqualTo("/acme/jobs/1")).willReturn(ok(page)));

        var url = baseUrl + "/acme/jobs/1";
        var result = resolver.resolveCompanyWebsites(List.of(
                new CompanyDomainResolverPort.JobRef(url, "Acme", "greenhouse")), 100);

        assertTrue(result.isEmpty() || !result.containsKey(url),
                "no shared token must yield no metadata");
    }

    private String nextJobPath() {
        return "/gupy/techco/jobs/" + (++jobSeq);
    }
}