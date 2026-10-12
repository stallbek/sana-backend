package com.is.aigul.service;

import com.is.aigul.dto.CommandDTO;
import com.is.aigul.dto.SellItemDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermMemoryServiceTest {

    @Test
    void memoryIsPerUserAndCappedAtThreeTurns() {
        ShortTermMemoryService service = new ShortTermMemoryService();

        service.rememberPendingCommands(7L, "turn 1", List.of(addCard(item("Atlas", 1))));
        service.rememberPendingCommands(7L, "turn 2", List.of(addCard(item("Atlas", 2))));
        service.rememberPendingCommands(7L, "turn 3", List.of(addCard(item("Atlas", 3))));
        service.rememberPendingCommands(7L, "turn 4", List.of(addCard(item("Atlas", 4))));
        service.rememberPendingCommands(8L, "other user", List.of(addCard(item("Silk", 9))));

        assertThat(service.getRecentMemory(7L)).hasSize(3);
        assertThat(service.getRecentMemory(7L)).extracting(ShortTermMemoryService.TurnContext::getUserMessage)
                .containsExactly("turn 2", "turn 3", "turn 4");
        assertThat(service.getRecentMemory(8L)).extracting(ShortTermMemoryService.TurnContext::getUserMessage)
                .containsExactly("other user");
    }

    @Test
    void clearMemoryRemovesOnlyThatUsersPendingTurns() {
        ShortTermMemoryService service = new ShortTermMemoryService();
        service.rememberPendingCommands(7L, "user 7", List.of(addCard(item("Atlas", 1))));
        service.rememberPendingCommands(8L, "user 8", List.of(addCard(item("Silk", 2))));

        service.clearMemory(7L);

        assertThat(service.getRecentMemory(7L)).isEmpty();
        assertThat(service.getRecentMemory(8L)).hasSize(1);
    }

    @Test
    void getCardsAreNotStoredAsPendingInventoryMemory() {
        ShortTermMemoryService service = new ShortTermMemoryService();
        CommandDTO getCard = new CommandDTO();
        getCard.setOperation("GET");

        service.rememberPendingCommands(7L, "show sales", List.of(getCard));

        assertThat(service.getRecentMemory(7L)).isEmpty();
    }

    @Test
    void storedCardsAreDefensiveCopies() {
        ShortTermMemoryService service = new ShortTermMemoryService();
        CommandDTO card = addCard(item("Atlas", 1));

        service.rememberPendingCommands(7L, "add atlas", List.of(card));
        card.getItems().get(0).setQuantity(99);

        assertThat(service.getRecentMemory(7L).get(0).getCommandCards().get(0).getItems().get(0).getQuantity())
                .isEqualTo(1);
    }

    private CommandDTO addCard(SellItemDTO... items) {
        CommandDTO command = new CommandDTO();
        command.setOperation("ADD");
        command.setItems(List.of(items));
        return command;
    }

    private SellItemDTO item(String productName, int quantity) {
        SellItemDTO item = new SellItemDTO();
        item.setProductName(productName);
        item.setLocation("Osh");
        item.setQuantity(quantity);
        return item;
    }
}
