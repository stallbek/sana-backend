package com.is.aigul.config;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "DB_URL=jdbc:h2:mem:security-smoke;DB_CLOSE_DELAY=-1", "DB_USERNAME=sa", "DB_PASSWORD=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop", "walletwallet.api-key=",
        "deepseek.apikey=", "openai.apikey=", "elevenlabs.apikey=", "app.cors.allowed-origins="
})
@AutoConfigureMockMvc
class ApplicationSecurityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test void realApplicationRequiresTokensAndUnconfiguredWalletDoesNotCallProvider() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/api/products")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/passes").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        var registration = mvc.perform(post("/api/auth/register").contentType("application/json")
                .content("{\"identifier\":\"demo@example.test\",\"password\":\"demo-password-only\"}"))
                .andExpect(status().isCreated()).andReturn();
        String token = mapper.readTree(registration.getResponse().getContentAsString()).get("token").asText();
        mvc.perform(get("/api/products").header("token", token)).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(post("/api/passes").header("token", token).contentType("application/json").content("{}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/inventory/command").header("token", token).contentType("application/json")
                .content("{\"operation\":\"GET\",\"message\":\"SELECT * FROM users\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_OPERATION"));
        mvc.perform(post("/api/inventory/command").header("token", token).contentType("application/json")
                .content("{\"operation\":\"CREATE\",\"productName\":\"Demo\",\"quantity\":-1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/logout").header("token", token)).andExpect(status().isOk());
        mvc.perform(get("/api/products").header("token", token)).andExpect(status().isUnauthorized());
    }
}
