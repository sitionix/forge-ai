package com.sitionix.forgeai.api.remoteaccess;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RemoteAccessColdBootstrapControllerTest {
    @Test void oneLocalBrowserActionStartsOnlyTheFixedSetup() {
        var calls=new AtomicInteger();
        var controller=new RemoteAccessColdBootstrapController(new RemoteAccessColdBootstrapController.Bridge() {
            public boolean ready() { return false; }
            public void prepare() { calls.incrementAndGet(); }
        });
        var request=local("GET");
        var state=controller.state(request,new MockHttpServletResponse());
        assertThat(state.status()).isEqualTo("COLD");
        assertThat(state.csrfToken()).isNotBlank();
        request.setMethod("POST");
        request.addHeader("Origin","http://127.0.0.1:9099");
        request.addHeader("X-CSRF-TOKEN",state.csrfToken());
        assertThat(controller.prepare(request,new MockHttpServletResponse()).getStatusCode().value()).isEqualTo(202);
        assertThat(calls).hasValue(1);
    }

    @Test void remoteOrCrossSiteBrowserCannotStartSetup() {
        var calls=new AtomicInteger();
        var controller=new RemoteAccessColdBootstrapController(new RemoteAccessColdBootstrapController.Bridge() {
            public boolean ready() { return false; }
            public void prepare() { calls.incrementAndGet(); }
        });
        var local=local("GET");
        var csrf=controller.state(local,new MockHttpServletResponse()).csrfToken();
        local.setMethod("POST");
        local.addHeader("Origin","http://evil.example");
        local.addHeader("X-CSRF-TOKEN",csrf);
        assertThat(controller.prepare(local,new MockHttpServletResponse()).getStatusCode().value()).isEqualTo(403);
        var remote=local("POST");remote.setRemoteAddr("192.0.2.10");
        remote.addHeader("Origin","http://127.0.0.1:9099");remote.addHeader("X-CSRF-TOKEN",csrf);
        remote.setSession(local.getSession(false));
        assertThat(controller.prepare(remote,new MockHttpServletResponse()).getStatusCode().value()).isEqualTo(403);
        assertThat(calls).hasValue(0);
    }

    @Test void failedSetupIsReportedInsteadOfAnEndlessPreparingState() {
        var controller=new RemoteAccessColdBootstrapController(new RemoteAccessColdBootstrapController.Bridge() {
            public boolean ready() { return false; }
            public boolean failed() { return true; }
            public void prepare() { throw new AssertionError("No preparation on GET"); }
        });
        assertThat(controller.state(local("GET"),new MockHttpServletResponse()).status()).isEqualTo("FAILED");
    }

    private MockHttpServletRequest local(String method) {
        var request=new MockHttpServletRequest(method,"/api/v1/infrastructure/agents/remote-access/bootstrap");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Host","127.0.0.1:9099");
        request.addHeader("Sec-Fetch-Site","same-origin");
        return request;
    }
}
