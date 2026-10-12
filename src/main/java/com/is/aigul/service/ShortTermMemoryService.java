package com.is.aigul.service;

import tools.jackson.databind.ObjectMapper;
import com.is.aigul.dto.CommandDTO;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ShortTermMemoryService {

    private static final int MAX_MEMORY_SIZE = 3;

    private final Map<Long, LinkedList<TurnContext>> userMemory = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void rememberPendingCommands(Long userId, String userMessage, List<CommandDTO> commandCards) {
        if (userId == null || commandCards == null || commandCards.isEmpty() || !hasPendingInventoryCommand(commandCards)) {
            return;
        }

        List<CommandDTO> copiedCards = copyCommands(commandCards);
        userMemory.compute(userId, (id, history) -> {
            if (history == null) {
                history = new LinkedList<>();
            }
            history.addLast(new TurnContext(userMessage, copiedCards));
            while (history.size() > MAX_MEMORY_SIZE) {
                history.removeFirst();
            }
            return history;
        });
    }

    public List<TurnContext> getRecentMemory(Long userId) {
        if (userId == null) {
            return List.of();
        }
        LinkedList<TurnContext> history = userMemory.get(userId);
        if (history == null) {
            return List.of();
        }
        return new ArrayList<>(history);
    }

    public void clearMemory(Long userId) {
        if (userId != null) {
            userMemory.remove(userId);
        }
    }

    private boolean hasPendingInventoryCommand(List<CommandDTO> commandCards) {
        return commandCards.stream()
                .anyMatch(card -> card != null
                        && card.getOperation() != null
                        && !"GET".equalsIgnoreCase(card.getOperation()));
    }

    private List<CommandDTO> copyCommands(List<CommandDTO> commandCards) {
        return commandCards.stream()
                .map(this::copyCommand)
                .toList();
    }

    private CommandDTO copyCommand(CommandDTO command) {
        return objectMapper.convertValue(command, CommandDTO.class);
    }

    @Data
    @AllArgsConstructor
    public static class TurnContext {
        private String userMessage;
        private List<CommandDTO> commandCards;
    }
}
