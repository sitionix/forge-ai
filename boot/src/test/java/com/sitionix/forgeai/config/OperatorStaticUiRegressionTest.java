package com.sitionix.forgeai.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OperatorStaticUiRegressionTest {

    private static final Path OPERATOR_UI_DIR = Path.of("src/main/resources/static/operator");

    @Test
    void givenTicketsPage_whenRendered_thenKeepExistingPrimaryActions() throws Exception {
        final String html = this.read("index.html");

        assertThat(html)
                .contains("href=\"../actuator/health\">Health</a>")
                .contains("id=\"refreshTickets\"")
                .contains(">Refresh</button>")
                .doesNotContain("id=\"navToggle\"")
                .doesNotContain("class=\"nav-toggle\"")
                .doesNotContain(">Menu<");
    }

    @Test
    void givenOperatorPages_whenRendered_thenUseExistingSideNavigationLinksOnly() throws Exception {
        for (final String page : List.of("index.html", "ticket.html", "agents.html")) {
            final String html = this.read(page);

            assertThat(html)
                    .as(page)
                    .contains("class=\"side-nav\"")
                    .contains("href=\"./index.html\"")
                    .contains(">Tickets</a>")
                    .contains("href=\"./agents.html\"")
                    .contains(">Agents</a>")
                    .doesNotContain("id=\"navToggle\"")
                    .doesNotContain("class=\"nav-toggle\"")
                    .doesNotContain(">Menu<");
        }
    }

    @Test
    void givenOperatorCss_whenSidebarIsFixed_thenContentGetsGutterWithoutReplacingButtons() throws Exception {
        final String css = this.read("operator-ui.css");

        assertThat(css)
                .contains(".side-nav {")
                .contains("position: fixed;")
                .contains("width: 150px;")
                .contains(".shell {")
                .contains("margin-left: 196px;")
                .contains("@media (max-width: 1000px)")
                .contains("margin-left: auto;")
                .doesNotContain(".nav-toggle")
                .doesNotContain("nav-collapsed")
                .doesNotContain("--nav-width");
    }

    private String read(final String fileName) throws Exception {
        return Files.readString(OPERATOR_UI_DIR.resolve(fileName), StandardCharsets.UTF_8);
    }
}
