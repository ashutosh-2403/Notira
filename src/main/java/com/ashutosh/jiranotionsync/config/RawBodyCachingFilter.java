package com.ashutosh.jiranotionsync.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Servlet filter that captures the raw request body before Spring consumes it.
 * We need the raw body to verify the HMAC-SHA256 signature from Jira.
 * Without this, once Spring reads the body to deserialize the JSON,
 * it's gone and we can't verify the signature.
 */
@Component
@Slf4j
public class RawBodyCachingFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (request instanceof HttpServletRequest httpRequest) {
            // Only cache body for webhook endpoint
            if (httpRequest.getRequestURI().contains("/api/webhooks")) {
                CachedBodyHttpServletRequest cachedRequest =
                        new CachedBodyHttpServletRequest(httpRequest);
                chain.doFilter(cachedRequest, response);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * Wraps HttpServletRequest to allow reading body multiple times.
     */
    public static class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

        private final byte[] cachedBody;

        public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.cachedBody = request.getInputStream().readAllBytes();
            // Store raw body as request attribute for HMAC verification
            request.setAttribute("rawBody",
                    new String(cachedBody, StandardCharsets.UTF_8));
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream byteArrayInputStream =
                    new ByteArrayInputStream(cachedBody);
            return new ServletInputStream() {
                @Override public int read() { return byteArrayInputStream.read(); }
                @Override public boolean isFinished() { return byteArrayInputStream.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {}
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
