package com.is.aigul.config;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Limits JSON/text streams even when the client omits Content-Length. */
final class BoundedRequestWrapper extends HttpServletRequestWrapper {
    private final ServletInputStream input;
    BoundedRequestWrapper(HttpServletRequest request, long limit) throws IOException {
        super(request);
        ServletInputStream delegate = request.getInputStream();
        input = new ServletInputStream() {
            private long count;
            @Override public int read() throws IOException {
                int value = delegate.read();
                if (value != -1 && ++count > limit) throw new IOException("Request body limit exceeded");
                return value;
            }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                int allowed = (int) Math.min(length, limit - count + 1);
                int read = delegate.read(buffer, offset, allowed);
                if (read > 0 && (count += read) > limit) throw new IOException("Request body limit exceeded");
                return read;
            }
            @Override public boolean isFinished() { return delegate.isFinished(); }
            @Override public boolean isReady() { return delegate.isReady(); }
            @Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }
        };
    }
    @Override public ServletInputStream getInputStream() { return input; }
    @Override public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    }
}
