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
                .contains("id=\"refreshTickets\"")
                .contains(">Refresh</button>")
                .doesNotContain("href=\"../actuator/health\">Health</a>")
                .doesNotContain("id=\"navToggle\"");
    }

    @Test
    void givenTicketPage_whenRendered_thenKeepExistingTicketGraphActions() throws Exception {
        final String html = this.read("ticket.html");

        assertThat(html)
                .contains("id=\"openTask\"")
                .contains(">Task</button>")
                .contains("id=\"executeTicket\"")
                .contains(">Execute</button>")
                .contains("id=\"resetLayout\"")
                .contains(">Reset Layout</button>")
                .contains("id=\"refreshGraph\"")
                .contains(">Refresh</button>")
                .doesNotContain("id=\"navToggle\"");
    }

    @Test
    void givenOperatorStaticPages_whenRendered_thenKeepTicketAndLaneFlows() {
        assertThat(OPERATOR_UI_DIR.resolve("new-task.html"))
                .exists();
        assertThat(OPERATOR_UI_DIR.resolve("lane.html"))
                .exists();
        assertThat(OPERATOR_UI_DIR.resolve("agents.html"))
                .exists();
    }

    @Test
    void givenLanePage_whenRendered_thenShowDependenciesInputsAndSession() throws Exception {
        final String html = this.read("lane.html");

        assertThat(html)
                .contains("id=\"laneDependencies\"")
                .contains(">Dependencies</h2>")
                .contains("id=\"laneInputs\"")
                .contains("id=\"laneEvents\"");
    }

    @Test
    void givenAgentsPage_whenRendered_thenUseSidebarNavigationAndRefreshAction() throws Exception {
        final String html = this.read("agents.html");

        assertThat(html)
                .contains("id=\"refreshAgents\"")
                .contains(">Refresh</button>")
                .contains("id=\"resourceContent\"")
                .contains("wrap=\"soft\"")
                .doesNotContain("href=\"./index.html\">Tickets</a>")
                .doesNotContain("id=\"navToggle\"");
    }

    @Test
    void givenOperatorJs_whenRendered_thenKeepSidebarEntriesAndPageHandlers() throws Exception {
        final String js = this.read("operator-ui.js");

        assertThat(js)
                .contains("function initSidebar()")
                .contains("href=\"./index.html\"")
                .contains("<strong>Tickets</strong>")
                .contains("href=\"./new-task.html\"")
                .contains("<strong>New Task</strong>")
                .contains("href=\"./agents.html\"")
                .contains("<strong>Agents</strong>")
                .contains("href=\"../actuator/health\"")
                .contains("if (page === 'new-task')")
                .contains("if (page === 'lane')")
                .contains("if (page === 'agents')")
                .contains("loadAgentsConfig")
                .contains("saveSelectedResource")
                .contains("formatEditableResourceContent")
                .contains("JSON.stringify(JSON.parse(content), null, 2)")
                .contains("renderLaneDependencies(data.dependencies || [])")
                .contains("function renderLaneEventMessage")
                .contains("jsonEventPreview")
                .contains("connectionColor(sourceStatus)")
                .contains("connectionMarkerId(sourceStatus)")
                .doesNotContain("class=\"side-nav\"")
                .doesNotContain("id=\"navToggle\"");
    }

    @Test
    void givenOperatorCss_whenRendered_thenKeepSidebarLayoutAndAgentsStyles() throws Exception {
        final String css = this.read("operator-ui.css");

        assertThat(css)
                .contains("--sidebar-width: 244px;")
                .contains("body.has-sidebar")
                .contains(".operator-sidebar")
                .contains(".sidebar-link")
                .contains(".shell {")
                .contains("margin: 0 auto;")
                .contains(".agents-grid")
                .contains(".agent-card")
                .contains("grid-template-columns: minmax(220px, 0.72fr) minmax(390px, 1.12fr) minmax(320px, 0.92fr);")
                .contains(".config-editor-panel")
                .contains(".dependency-card")
                .contains(".event-preview")
                .contains(".conversation-event .event-details")
                .contains("white-space: pre-wrap;")
                .contains("@media (max-width: 1280px)")
                .contains("@media (max-width: 1000px)")
                .doesNotContain(".side-nav")
                .doesNotContain(".nav-toggle")
                .doesNotContain("margin-left: 196px");
    }

    private String read(final String fileName) throws Exception {
        return Files.readString(OPERATOR_UI_DIR.resolve(fileName), StandardCharsets.UTF_8);
    }
}
