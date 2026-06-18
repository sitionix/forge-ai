package com.sitionix.forgeai.api;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class TerminalTtyResolver {

    private static final String TERMINAL_TTY_HEADER = "X-Terminal-TTY";

    public String resolve() {
        final ServletRequestAttributes requestAttributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (requestAttributes == null) {
            return null;
        }
        return requestAttributes.getRequest().getHeader(TERMINAL_TTY_HEADER);
    }
}
