package biz.thonbecker.personal.staticsite;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.WaitUntilState;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StaticSiteBrowserTest {

    private static final String VERSE_FRAGMENT = "<p class=\"verse-text\">In the beginning was the Word. (John 1:1)</p>"
            + "<p class=\"verse-reference\">CSB</p>"
            + "<button class=\"verse-toggle\" type=\"button\" "
            + "data-english=\"In the beginning was the Word. (John 1:1)\" "
            + "data-greek=\"Ἐν ἀρχῇ ἦν ὁ λόγος. (John 1:1)\">Show Greek</button>";
    private static final Path STATIC_SITE = Path.of("static-site").toAbsolutePath();
    private static final Path STATIC_IMAGES =
            Path.of("src/main/resources/static/images").toAbsolutePath();
    private static final Path CLASSPATH_STATIC =
            Path.of("src/main/resources/static").toAbsolutePath();
    private static final List<String> browserErrors = new ArrayList<>();
    private static HttpServer server;
    private static Playwright playwright;
    private static Browser browser;
    private static String baseUrl;

    @BeforeAll
    static void startBrowserAndServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", StaticSiteBrowserTest::serveRequest);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        Files.createDirectories(Path.of("target/playwright"));

        playwright = Playwright.create();
        final var launchOptions = new BrowserType.LaunchOptions().setHeadless(true);
        final var firefoxPath = System.getenv("PLAYWRIGHT_FIREFOX_PATH");
        if (firefoxPath != null && !firefoxPath.isBlank()) {
            launchOptions.setExecutablePath(Path.of(firefoxPath));
        }
        browser = playwright.firefox().launch(launchOptions);
    }

    @BeforeEach
    void clearBrowserErrors() {
        browserErrors.clear();
    }

    @AfterAll
    static void stopBrowserAndServer() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void homepageWorksAcrossDesktopAndMobile() {
        final var page = newPage(1440, 1000);

        page.navigate(baseUrl + "/", new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.locator("h1").waitFor();
        page.waitForFunction("document.querySelector('.experience-line span')?.textContent.includes('18')");
        assertTrue(page.locator(".experience-line span").textContent().contains("18"));
        assertTrue(page.locator("#primary-navigation").isVisible());
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(Path.of("target/playwright/homepage-desktop.png"))
                .setFullPage(true));

        page.setViewportSize(390, 844);
        final var menu = page.locator("[data-menu-toggle]");
        assertTrue(menu.isVisible());
        menu.click();
        assertTrue(page.locator("#primary-navigation").getAttribute("class").contains("is-open"));
        page.keyboard().press("Escape");
        assertFalse(page.locator("#primary-navigation").getAttribute("class").contains("is-open"));

        menu.click();
        page.mouse().click(10, 400);
        assertFalse(page.locator("#primary-navigation").getAttribute("class").contains("is-open"));
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(Path.of("target/playwright/homepage-mobile.png"))
                .setFullPage(true));

        page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(ColorScheme.DARK));
        page.locator("h1").waitFor();
        assertTrue(page.locator("[data-theme-toggle]").count() == 0);
        assertTrue(page.locator("html").getAttribute("data-theme") == null);
        assertTrue(Objects.equals(
                page.locator(".site-header .brand").evaluate("element => getComputedStyle(element).color"),
                "rgb(255, 255, 255)"));
        assertTrue(Objects.equals(
                page.locator("h1").evaluate("element => getComputedStyle(element).color"), "rgb(255, 255, 255)"));
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(Path.of("target/playwright/homepage-dark.png"))
                .setFullPage(true));
        assertTrue(browserErrors.isEmpty(), () -> "Browser errors: " + browserErrors);
    }

    @Test
    void greekVerseUsesNotoSerifWhileUiStaysNotoSans() {
        final var page = newPage(1280, 900);

        page.navigate(baseUrl + "/", new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.locator(".verse-toggle").waitFor();
        page.waitForFunction(
                "[...document.fonts].some(font => font.family.replaceAll('\"', '') === 'Noto Sans' && font.status === 'loaded')");
        assertTrue(String.valueOf(page.locator("body").evaluate("element => getComputedStyle(element).fontFamily"))
                .contains("Noto Sans"));
        assertFalse(String.valueOf(page.locator("body").evaluate("element => getComputedStyle(element).fontFamily"))
                .contains("Inter"));
        assertTrue(page.locator(".brand-mark-light").getAttribute("src").endsWith("/images/brand/b-transparent.svg"));
        assertTrue(String.valueOf(page.locator(".portrait").evaluate("element => getComputedStyle(element).clipPath"))
                .contains("circle"));

        page.locator(".verse-toggle").click();
        page.locator(".verse-text.greek").waitFor();
        assertTrue(Objects.equals("grc", page.locator(".verse-text").getAttribute("lang")));
        assertTrue(page.locator(".verse-text").textContent().contains("λόγος"));
        page.waitForFunction("document.fonts.check('16px \"Noto Serif\"', 'Ἐν ἀρχῇ ἦν ὁ λόγος')");
        assertTrue(
                String.valueOf(page.locator(".verse-text").evaluate("element => getComputedStyle(element).fontFamily"))
                        .contains("Noto Serif"));

        page.navigate(
                baseUrl + "/religious-freedom",
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.locator(".scripture blockquote").waitFor();
        assertTrue(String.valueOf(page.locator(".scripture blockquote")
                        .evaluate("element => getComputedStyle(element).fontFamily"))
                .contains("Noto Serif"));
        assertTrue(browserErrors.isEmpty(), () -> "Browser errors: " + browserErrors);
    }

    @Test
    void reflectionPageRendersItsCitationAndTheme() {
        final var page = newPage(1280, 1000);

        page.navigate(
                baseUrl + "/religious-freedom",
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.locator("h1").waitFor();
        final var citation = page.locator(".scripture cite");
        assertTrue(citation.count() == 1);
        assertTrue(citation.textContent().contains("Galatians 5:13"));
        assertTrue(Objects.equals(citation.evaluate("element => getComputedStyle(element).display"), "block"));
        page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(ColorScheme.DARK));
        assertTrue(page.locator("[data-theme-toggle]").count() == 0);
        assertTrue(page.locator("html").getAttribute("data-theme") == null);
        assertTrue(Objects.equals(
                page.locator(".site-header .brand").evaluate("element => getComputedStyle(element).color"),
                "rgb(255, 255, 255)"));
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(Path.of("target/playwright/religious-freedom-dark.png"))
                .setFullPage(true));

        page.setViewportSize(390, 844);
        final var menu = page.locator("[data-menu-toggle]");
        assertTrue(menu.isVisible());
        assertFalse(page.locator("#primary-navigation").isVisible());
        menu.click();
        assertTrue(page.locator("#primary-navigation").isVisible());
        assertTrue(browserErrors.isEmpty(), () -> "Browser errors: " + browserErrors);
    }

    private static Page newPage(final int width, final int height) {
        final var page = browser.newPage(new Browser.NewPageOptions().setViewportSize(width, height));
        page.route(url -> url.contains("app.thonbecker.biz"), StaticSiteBrowserTest::fulfillAppRequest);
        page.route(
                url -> url.contains("htmx.org"),
                route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                        .setStatus(200)
                        .setContentType("application/javascript")
                        .setBody("""
                        document.addEventListener('DOMContentLoaded', () => {
                          document.querySelectorAll('[hx-get][hx-trigger="load"]').forEach(async (element) => {
                            const response = await fetch(element.getAttribute('hx-get'));
                            element.innerHTML = await response.text();
                          });
                        });
                        """)));
        page.route(
                url -> url.contains("static/array.js") || url.contains("e.thonbecker.biz"),
                route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                        .setStatus(200)
                        .setContentType("application/javascript")
                        .setBody("")));
        page.onConsoleMessage(message -> {
            if (message.type().equals("error")) {
                browserErrors.add("console: " + message.text());
            }
        });
        page.onRequestFailed(request -> browserErrors.add("request: " + request.url()));
        return page;
    }

    private static void fulfillAppRequest(final com.microsoft.playwright.Route route) {
        final var path = URI.create(route.request().url()).getPath();
        if (path.equals("/api/experience/count")) {
            route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                    .setStatus(200)
                    .setContentType("text/plain; charset=UTF-8")
                    .setBody("18+ years of experience"));
            return;
        }
        if (path.equals("/api/bible/verse-of-day/fragment")) {
            route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                    .setStatus(200)
                    .setContentType("text/html; charset=UTF-8")
                    .setBody(VERSE_FRAGMENT));
            return;
        }
        if (path.equals("/api/joke")) {
            route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                    .setStatus(200)
                    .setContentType("text/plain; charset=UTF-8")
                    .setBody("https://example.invalid/joke.mp3"));
            return;
        }
        route.abort();
    }

    private static void serveRequest(final HttpExchange exchange) throws IOException {
        final var path = URI.create(exchange.getRequestURI().toString()).getPath();
        final var response = responseFor(path);
        exchange.getResponseHeaders().put("Content-Type", List.of(response.contentType()));
        exchange.sendResponseHeaders(response.status(), response.body().length);
        try (var output = exchange.getResponseBody()) {
            output.write(response.body());
        }
    }

    private static Response responseFor(final String path) throws IOException {
        if (path.equals("/api/experience/count")) {
            return Response.ok("18", "text/plain; charset=UTF-8");
        }
        if (path.equals("/api/bible/verse-of-day/fragment")) {
            return Response.ok(VERSE_FRAGMENT, "text/html; charset=UTF-8");
        }

        final var relativePath = path.equals("/")
                ? "index.html"
                : path.equals("/religious-freedom") ? "religious-freedom.html" : path.substring(1);
        final var root = path.startsWith("/images/") ? STATIC_IMAGES : STATIC_SITE;
        final var relativeFile =
                path.startsWith("/images/") ? relativePath.substring("images/".length()) : relativePath;
        var file = root.resolve(relativeFile).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            file = CLASSPATH_STATIC.resolve(relativeFile).normalize();
            if (!file.startsWith(CLASSPATH_STATIC) || !Files.isRegularFile(file)) {
                return Response.notFound();
            }
        }
        return Response.ok(Files.readAllBytes(file), contentType(file));
    }

    private static String contentType(final Path file) {
        return Map.of(
                        ".css", "text/css; charset=UTF-8",
                        ".html", "text/html; charset=UTF-8",
                        ".js", "application/javascript; charset=UTF-8",
                        ".svg", "image/svg+xml",
                        ".png", "image/png",
                        ".ico", "image/x-icon",
                        ".webmanifest", "application/manifest+json",
                        ".woff2", "font/woff2")
                .entrySet()
                .stream()
                .filter(entry -> file.toString().endsWith(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("application/octet-stream");
    }

    private record Response(int status, byte[] body, String contentType) {

        private static Response ok(final String body, final String contentType) {
            return ok(body.getBytes(StandardCharsets.UTF_8), contentType);
        }

        private static Response ok(final byte[] body, final String contentType) {
            return new Response(200, body, contentType);
        }

        private static Response notFound() {
            return new Response(404, new byte[0], "text/plain; charset=UTF-8");
        }
    }
}
