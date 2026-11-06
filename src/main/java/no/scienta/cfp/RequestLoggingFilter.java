package no.scienta.cfp;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * One log line per request: method, path with query (as the client sent it, before the trailing slash is stripped),
 * status and duration, e.g. {@code POST /speakers/ 201 12ms}. No bodies, so no emails or bios in the log.
 * Logger "access", so it can be turned off with logging.level.access=WARN.
 */
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("access");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        String query = request.getQueryString();
        String target = query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
        boolean failed = true;
        try {
            chain.doFilter(request, response);
            failed = false;
        } finally {
            long millis = (System.nanoTime() - started) / 1_000_000;
            // an exception escaping the chain becomes a 500 after this filter has returned
            int status = failed ? 500 : response.getStatus();
            log.info("{} {} {} {}ms", request.getMethod(), target, status, millis);
        }
    }
}
