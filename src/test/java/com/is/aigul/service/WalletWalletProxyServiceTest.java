package com.is.aigul.service;

import com.is.aigul.config.WalletWalletProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class WalletWalletProxyServiceTest {

    private MockWebServer server;
    private WalletWalletProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        properties = new WalletWalletProperties();
        properties.setBaseUrl(server.url("/").toString());
        properties.setApiKey("server-secret");
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setReadTimeout(Duration.ofSeconds(1));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void forwardsOriginalJsonWithServerCredentialAndDefaultEnvelope() throws Exception {
        byte[] json = "{ \"passes\": [ {\"type\":\"apple\"} ] }\n".getBytes(StandardCharsets.UTF_8);
        server.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"ok\":true}"));

        ResponseEntity<byte[]> response = new WalletWalletProxyService(properties).createPass(json, null);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/api/passes");
        assertThat(request.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer server-secret");
        assertThat(request.getBody().readByteArray()).isEqualTo(json);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/json");
    }

    @Test
    void binaryModePreservesBodyAndOnlyApprovedHeaders() throws Exception {
        byte[] pass = new byte[]{0x50, 0x4b, 0x03, 0x04, 0x00, (byte) 0xff};
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/vnd.apple.pkpass")
                .setHeader("Content-Disposition", "attachment; filename=\"card.pkpass\"")
                .setHeader("X-Serial-Number", "serial-7")
                .setHeader("X-Google-Save-Url", "https://pay.example/save")
                .setHeader("X-Pass-Url", "https://wallet.example/pass")
                .setHeader("Retry-After", "15")
                .setHeader("Server", "provider")
                .setHeader("Connection", "close")
                .setBody(new okio.Buffer().write(pass)));

        ResponseEntity<byte[]> response = new WalletWalletProxyService(properties)
                .createPass("{}".getBytes(StandardCharsets.UTF_8), "binary");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/api/passes?format=binary");
        assertThat(response.getBody()).isEqualTo(pass);
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .isEqualTo("attachment; filename=\"card.pkpass\"");
        assertThat(response.getHeaders().getFirst("X-Serial-Number")).isEqualTo("serial-7");
        assertThat(response.getHeaders().getFirst("X-Google-Save-Url")).isEqualTo("https://pay.example/save");
        assertThat(response.getHeaders().getFirst("X-Pass-Url")).isEqualTo("https://wallet.example/pass");
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("15");
        assertThat(response.getHeaders().containsHeader("Server")).isFalse();
        assertThat(response.getHeaders().containsHeader("Connection")).isFalse();
    }

    @Test
    void sanitizesUpstreamErrors() throws Exception {
        for (int status : new int[]{400, 401, 429, 500}) {
            String body = "{\"status\":" + status + "}";
            server.enqueue(new MockResponse()
                    .setResponseCode(status)
                    .setHeader("Content-Type", "application/json")
                    .setBody(body));

            ResponseEntity<byte[]> response = new WalletWalletProxyService(properties)
                    .createPass("{}".getBytes(StandardCharsets.UTF_8), "json");

            assertThat(response.getStatusCode().value()).isEqualTo(status == 429 ? 429 : 502);
            assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo("{\"error\":\"Wallet pass provider unavailable\"}");
            server.takeRequest();
        }
    }

    @Test
    void rejectsInvalidInputAndMissingConfigurationWithoutCallingProvider() {
        WalletWalletProxyService service = new WalletWalletProxyService(properties);
        properties.setMaxRequestBytes(2);
        MockHttpServletRequest oversizedRequest = new MockHttpServletRequest();
        oversizedRequest.setContent(new byte[]{1, 2, 3});

        assertThat(service.createPass(new byte[3], null).getStatusCode())
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(service.createPass(oversizedRequest, null).getStatusCode())
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(service.createPass(new byte[0], "xml").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        properties.setApiKey(" ");
        ResponseEntity<byte[]> missing = service.createPass(new byte[0], null);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(new String(missing.getBody(), StandardCharsets.UTF_8))
                .isEqualTo("{\"error\":\"Wallet pass service is not configured\"}");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void convertsConnectionFailureToBadGateway() throws Exception {
        String unavailableUrl = server.url("/").toString();
        server.shutdown();
        server = new MockWebServer();
        properties.setBaseUrl(unavailableUrl);

        ResponseEntity<byte[]> response = new WalletWalletProxyService(properties)
                .createPass("{}".getBytes(StandardCharsets.UTF_8), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8))
                .isEqualTo("{\"error\":\"Wallet pass provider unavailable\"}");
    }

    @Test
    void convertsReadTimeoutToBadGateway() {
        properties.setReadTimeout(Duration.ofMillis(50));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBodyDelay(1, TimeUnit.SECONDS)
                .setBody("{}"));

        ResponseEntity<byte[]> response = new WalletWalletProxyService(properties)
                .createPass("{}".getBytes(StandardCharsets.UTF_8), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8))
                .isEqualTo("{\"error\":\"Wallet pass provider unavailable\"}");
    }
}
