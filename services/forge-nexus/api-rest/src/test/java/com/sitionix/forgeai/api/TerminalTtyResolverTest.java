package com.sitionix.forgeai.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalTtyResolverTest {

    private TerminalTtyResolver terminalTtyResolver;

    @BeforeEach
    void setUp() {
        this.terminalTtyResolver = new TerminalTtyResolver();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void givenNoRequestContext_whenResolve_thenReturnNull() {
        //given
        RequestContextHolder.resetRequestAttributes();

        //when
        final String actual = this.terminalTtyResolver.resolve();

        //then
        assertThat(actual).isNull();
    }

    @Test
    void givenRequestWithHeader_whenResolve_thenReturnHeaderValue() {
        //given
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Terminal-TTY", "/dev/ttys012");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        //when
        final String actual = this.terminalTtyResolver.resolve();

        //then
        assertThat(actual).isEqualTo("/dev/ttys012");
    }
}
