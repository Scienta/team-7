package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.ServletException;

@ExtendWith(OutputCaptureExtension.class)
class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();

    @Test
    void logsMethodPathWithQueryAndStatus(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/talks/");
        request.setQueryString("sort=rating");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> ((MockHttpServletResponse) res).setStatus(404));
        assertTrue(output.getOut().matches("(?s).*GET /talks/\\?sort=rating 404 \\d+ms.*"), output.getOut());
    }

    @Test
    void anExceptionIsLoggedAs500AndStillThrown(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/speakers");
        assertThrows(ServletException.class, () -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            throw new ServletException("boom");
        }));
        assertTrue(output.getOut().matches("(?s).*POST /speakers 500 \\d+ms.*"), output.getOut());
    }
}
