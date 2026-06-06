package com.sitionix.forgeai.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OperatorUiStartupLoggerTest {

    private final OperatorUiStartupLogger logger = new OperatorUiStartupLogger(null, null);

    @Test
    void givenContextPath_whenOperatorUiUrl_thenIncludeContextPath() {
        assertThat(this.logger.operatorUiUrl(9099, "/fgaisox"))
                .isEqualTo("http://localhost:9099/fgaisox/operator/index.html");
    }

    @Test
    void givenRootContextPath_whenOperatorUiUrl_thenDoNotAddDoubleSlash() {
        assertThat(this.logger.operatorUiUrl(9098, "/"))
                .isEqualTo("http://localhost:9098/operator/index.html");
    }

    @Test
    void givenContextPathWithoutLeadingSlash_whenOperatorUiUrl_thenNormalizeUrl() {
        assertThat(this.logger.operatorUiUrl(9099, "fgaisox/"))
                .isEqualTo("http://localhost:9099/fgaisox/operator/index.html");
    }
}
