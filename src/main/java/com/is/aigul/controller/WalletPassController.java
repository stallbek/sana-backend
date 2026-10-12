package com.is.aigul.controller;

import com.is.aigul.service.ClientIpResolver;
import com.is.aigul.service.WalletPassRateLimitService;
import com.is.aigul.service.WalletWalletProxyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/passes")
public class WalletPassController {

    private final WalletWalletProxyService proxyService;
    private final WalletPassRateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    public WalletPassController(WalletWalletProxyService proxyService,
            WalletPassRateLimitService rateLimitService, ClientIpResolver clientIpResolver) {
        this.proxyService = proxyService;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> createPass(@RequestParam(required = false) String format,
            HttpServletRequest request) {
        WalletPassRateLimitService.Decision decision =
                rateLimitService.attempt(clientIpResolver.resolve(request));
        if (!decision.allowed()) {
            ResponseEntity<byte[]> response =
                    WalletWalletProxyService.error(HttpStatus.TOO_MANY_REQUESTS, "Too many requests");
            HttpHeaders headers = new HttpHeaders();
            headers.putAll(response.getHeaders());
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
            return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
        }
        return proxyService.createPass(request, format);
    }
}
