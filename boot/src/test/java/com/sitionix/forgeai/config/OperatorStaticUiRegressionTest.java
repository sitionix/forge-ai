package com.sitionix.forgeai.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OperatorStaticUiRegressionTest {

    private static final Path OPERATOR_UI_DIR = Path.of("src/main/resources/static/operator");

    @Test
    void givenTicketsPage_whenRendered_thenKeepExistingPrimaryActions() throws Exception {
        final String html = this.read("index.html");

        assertThat(html)
                .contains("href=\"../actuator/health\">Health</a>")
                .contains("href=\"./agents.html\">Agents</a>")
                .contains("id=\"refreshTickets\"")
                .contains(">Refresh</button>")
                .doesNotContain("class=\"side-nav\"")
                .doesNotContain("id=\"navToggle\"");
    }

    @Test
    void givenTicketPage_whenRendered_thenKeepExistingTicketActionsAndExposeAgents() throws Exception {
        final String html = this.read("ticket.html");

        assertThat(html)
                .contains("href=\"./index.html\">Tickets</a>")
                .contains("href=\"./agents.html\">Agents</a>")
                .contains("id=\"refreshGraph\"")
                .contains(">Refresh</button>")
                .doesNotContain("class=\"side-nav\"")
                .doesNotContain("id=\"navToggle\"");
    }

    @Test
    void givenAgentsPage_whenRendered_thenUseMainStyleHeroActions() throws Exception {
        final String html = this.read("agents.html");

        assertThat(html)
                .contains("href=\"./index.html\">Tickets</a>")
                .contains("id=\"refreshAgents\"")
                .contains(">Refresh</button>")
                .doesNotContain("class=\"side-nav\"")
                .doesNotContain("id=\"navToggle\"");
    }

    @Test
    void givenOperatorCss_whenRendered_thenKeepMainCenteredLayoutWithoutSidebar() throws Exception {
        final String css = this.read("operator-ui.css");

        assertThat(css)
                .contains(".shell {")
                .contains("width: min(1440px, calc(100vw - 48px));")
                .contains("margin: 0 auto;")
                .contains(".graph-shell {")
                .contains("width: min(1680px, calc(100vw - 40px));")
                .contains("@media (max-width: 1000px)")
                .doesNotContain(".side-nav")
                .doesNotContain(".nav-link")
                .doesNotContain(".nav-toggle")
                .doesNotContain("nav-collapsed")
                .doesNotContain("margin-left: 196px");
    }

    private String read(final String fileName) throws Exception {
        return Files.readString(OPERATOR_UI_DIR.resolve(fileName), StandardCharsets.UTF_8);
    }
}
