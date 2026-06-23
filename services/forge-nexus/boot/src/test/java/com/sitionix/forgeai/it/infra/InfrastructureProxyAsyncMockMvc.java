package com.sitionix.forgeai.it.infra;

import com.sitionix.forgeit.domain.endpoint.Endpoint;
import com.sitionix.forgeit.domain.endpoint.HttpMethod;
import com.sitionix.forgeit.domain.endpoint.mockmvc.MockmvcDefaultContext;
import com.sitionix.forgeit.mockmvc.api.QueryParams;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.StreamUtils;
import org.springframework.web.util.UriComponentsBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Component
public class InfrastructureProxyAsyncMockMvc {

    private static final String MOCKMVC_REQUEST_ROOT = "forge-it/mockmvc/default/request/";
    private static final String MOCKMVC_RESPONSE_ROOT = "forge-it/mockmvc/default/response/";

    private final MockMvc mockMvc;

    public InfrastructureProxyAsyncMockMvc(final MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    public <Req, Res> Builder<Req, Res> ping(final Endpoint<Req, Res> endpoint) {
        return new Builder<>(this.mockMvc, endpoint);
    }

    public static final class Builder<Req, Res> implements MockmvcDefaultContext {

        private final MockMvc mockMvc;
        private final Endpoint<Req, Res> endpoint;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final List<ResultMatcher> extraMatchers = new ArrayList<>();
        private Map<String, ?> queryParameters = Map.of();
        private String requestFixture;
        private String responseFixture;
        private Integer expectedStatus;

        private Builder(final MockMvc mockMvc, final Endpoint<Req, Res> endpoint) {
            this.mockMvc = mockMvc;
            this.endpoint = endpoint;
            if (endpoint.getMockmvcDefault() != null) {
                endpoint.getMockmvcDefault().applyDefaults(this);
            }
        }

        public Builder<Req, Res> withQueryParameters(final QueryParams queryParams) {
            if (queryParams != null) {
                this.queryParameters = queryParams.asMap();
            }
            return this;
        }

        public Builder<Req, Res> andExpectPath(final ResultMatcher matcher) {
            this.extraMatchers.add(matcher);
            return this;
        }

        @Override
        public Builder<Req, Res> withRequest(final String fixture) {
            this.requestFixture = fixture;
            return this;
        }

        @Override
        public Builder<Req, Res> expectResponse(final String fixture) {
            this.responseFixture = fixture;
            return this;
        }

        @Override
        public Builder<Req, Res> expectStatus(final int status) {
            this.expectedStatus = status;
            return this;
        }

        @Override
        public Builder<Req, Res> token(final String token) {
            this.headers.put("Authorization", token);
            return this;
        }

        @Override
        public Builder<Req, Res> header(final String name, final String value) {
            this.headers.put(name, value);
            return this;
        }

        @Override
        public Builder<Req, Res> cookie(final String name, final String value) {
            return this;
        }

        public void assertDefault() {
            try {
                ResultActions actions = this.mockMvc.perform(this.request());
                final var result = actions.andReturn();
                if (result.getRequest().isAsyncStarted()) {
                    actions = this.mockMvc.perform(asyncDispatch(result));
                }
                if (this.expectedStatus != null) {
                    actions.andExpect(status().is(this.expectedStatus));
                }
                if (this.responseFixture != null) {
                    actions.andExpect(content().json(resource(MOCKMVC_RESPONSE_ROOT + this.responseFixture), true));
                }
                for (final ResultMatcher matcher : this.extraMatchers) {
                    actions.andExpect(matcher);
                }
            } catch (final Exception exception) {
                throw new RuntimeException("Failed to assert async MockMvc endpoint contract", exception);
            }
        }

        private MockHttpServletRequestBuilder request() throws IOException {
            final String url = this.url();
            final MockHttpServletRequestBuilder request = this.endpoint.getMethod() == HttpMethod.POST ? post(url) : get(url);
            this.headers.forEach(request::header);
            if (this.requestFixture != null) {
                request.contentType(MediaType.APPLICATION_JSON).content(resource(MOCKMVC_REQUEST_ROOT + this.requestFixture));
            }
            return request;
        }

        private String url() {
            final UriComponentsBuilder builder = UriComponentsBuilder.fromPath(this.endpoint.getUrlBuilder().getTemplate());
            this.queryParameters.forEach((name, value) -> builder.queryParam(name, String.valueOf(value)));
            return builder.build(false).toUriString();
        }

        private static String resource(final String path) throws IOException {
            return StreamUtils.copyToString(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
        }
    }
}
