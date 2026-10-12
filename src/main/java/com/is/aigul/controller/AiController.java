package com.is.aigul.controller;

import tools.jackson.databind.JsonNode;
import com.is.aigul.repository.ProductRepository;
import com.is.aigul.service.AuthService;
import com.is.aigul.service.GptService;
import com.is.aigul.service.AiOperation;
import com.is.aigul.service.AiUsageLimitService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import tools.jackson.databind.ObjectMapper;
import com.is.aigul.dto.CommandDTO;
import com.is.aigul.service.CommandPreprocessorService;

@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final GptService gptService;
    private final AuthService authService;
    private final ProductRepository productRepository;
    private final CommandPreprocessorService commandPreprocessorService;
    private final AiUsageLimitService aiUsageLimitService;
    private final ObjectMapper mapper = new ObjectMapper();

    public AiController(GptService gptService, AuthService authService, ProductRepository productRepository,
            CommandPreprocessorService commandPreprocessorService, AiUsageLimitService aiUsageLimitService) {
        this.gptService = gptService;
        this.authService = authService;
        this.productRepository = productRepository;
        this.commandPreprocessorService = commandPreprocessorService;
        this.aiUsageLimitService = aiUsageLimitService;
    }

    @PostMapping(value = "/parse", consumes = "text/plain")
    public List<CommandDTO> parseCommand(@RequestBody String text, @RequestHeader("token") String token) throws Exception {
        Long userId = authService.getUserId(token);
        if (userId == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized");
        aiUsageLimitService.validateText(text);
        try (AiUsageLimitService.Permit ignored = aiUsageLimitService.reserve(userId, AiOperation.TEXT_COMMAND)) {
            List<String> knownProducts = productRepository.findAllNamesByUserId(userId);
            JsonNode gptJson = gptService.convertTextToJson(text, knownProducts, userId);

            JsonNode commandsArray = gptJson.get("commands");
            if (commandsArray == null || !commandsArray.isArray()) {
                throw new RuntimeException("Invalid response from GPT: 'commands' array missing.");
            }

            List<CommandDTO> responseCards = new java.util.ArrayList<>();
            for (JsonNode cmdNode : commandsArray) {
                CommandDTO cmd = mapper.treeToValue(cmdNode, CommandDTO.class);
                cmd = commandPreprocessorService.preprocess(cmd, userId);
                responseCards.add(cmd);
            }
            return responseCards;
        }
    }
}
