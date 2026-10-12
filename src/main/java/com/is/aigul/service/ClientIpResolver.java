package com.is.aigul.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

@Service
public class ClientIpResolver {
    public String resolve(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
