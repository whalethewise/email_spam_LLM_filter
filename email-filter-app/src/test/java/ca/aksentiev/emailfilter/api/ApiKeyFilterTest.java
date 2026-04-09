package ca.aksentiev.emailfilter.api;

import ca.aksentiev.emailfilter.config.ReloadApiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyFilterTest {

    @Test
    void validKeyAllowsRequest() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter(new ReloadApiProperties(8081, "secret-key"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/reload");
        request.addHeader("X-Api-Key", "secret-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void missingKeyReturns401() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter(new ReloadApiProperties(8081, "secret-key"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/scan");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void wrongKeyReturns401() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter(new ReloadApiProperties(8081, "secret-key"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/reload");
        request.addHeader("X-Api-Key", "wrong-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void nonApiPathSkipsFilter() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter(new ReloadApiProperties(8081, "secret-key"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void blankApiKeyConfigReturns500() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter(new ReloadApiProperties(8081, ""));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/reload");
        request.addHeader("X-Api-Key", "anything");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(500);
    }
}
