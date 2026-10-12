package com.is.aigul.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.is.aigul.dto.CommandDTO;
import com.is.aigul.dto.SellItemDTO;
import com.is.aigul.repository.LocationNamesRepository;
import com.is.aigul.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class FullServiceMemoryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void textFlowParsesFreshCommandEvenWhenMemoryContainsPendingCard() throws Exception {
        TestContext context = new TestContext();
        CommandDTO processedCard = addCard("Cotton", 3);
        ObjectNode root = commandRoot("ADD", "Cotton", 3);

        when(context.productRepository.findAllNamesByUserId(7L)).thenReturn(List.of("Atlas", "Cotton"));
        when(context.gptService.convertTextToJson("add 3 cotton", List.of("Atlas", "Cotton"), 7L))
                .thenReturn(root);
        when(context.preprocessor.preprocess(any(CommandDTO.class), eq(7L))).thenReturn(processedCard);

        List<CommandDTO> result = context.service.processTextFully("add 3 cotton", 7L);

        assertThat(result).containsExactly(processedCard);
        verify(context.gptService).convertTextToJson("add 3 cotton", List.of("Atlas", "Cotton"), 7L);
        verify(context.memoryService).rememberPendingCommands(7L, "add 3 cotton", List.of(processedCard));
        verifyNoMoreInteractions(context.memoryService);
    }

    @Test
    void shortCorrectionWithoutExplicitCardStillParsesAsFreshCommand() throws Exception {
        TestContext context = new TestContext();
        CommandDTO processedCard = sellCard("Unknown", 12);
        ObjectNode root = commandRoot("SELL", "Unknown", 12);

        when(context.productRepository.findAllNamesByUserId(7L)).thenReturn(List.of("Atlas"));
        when(context.gptService.convertTextToJson("actually 12", List.of("Atlas"), 7L)).thenReturn(root);
        when(context.preprocessor.preprocess(any(CommandDTO.class), eq(7L))).thenReturn(processedCard);

        List<CommandDTO> result = context.service.processTextFully("actually 12", 7L);

        assertThat(result).containsExactly(processedCard);
        verify(context.gptService).convertTextToJson("actually 12", List.of("Atlas"), 7L);
        verify(context.memoryService).rememberPendingCommands(7L, "actually 12", List.of(processedCard));
        verifyNoMoreInteractions(context.memoryService);
    }

    private ObjectNode commandRoot(String operation, String productName, int quantity) {
        ObjectNode command = mapper.createObjectNode();
        command.put("operation", operation);
        ObjectNode item = mapper.createObjectNode();
        item.put("productName", productName);
        item.put("quantity", quantity);
        command.set("items", mapper.createArrayNode().add(item));

        ObjectNode root = mapper.createObjectNode();
        root.set("commands", mapper.createArrayNode().add(command));
        return root;
    }

    private CommandDTO addCard(String productName, int quantity) {
        CommandDTO command = new CommandDTO();
        command.setOperation("ADD");
        command.setItems(List.of(item(productName, quantity)));
        return command;
    }

    private CommandDTO sellCard(String productName, int quantity) {
        CommandDTO command = new CommandDTO();
        command.setOperation("SELL");
        command.setItems(List.of(item(productName, quantity)));
        return command;
    }

    private SellItemDTO item(String productName, int quantity) {
        SellItemDTO item = new SellItemDTO();
        item.setProductName(productName);
        item.setQuantity(quantity);
        return item;
    }

    private class TestContext {
        private final WhisperService whisperService = mock(WhisperService.class);
        private final GptService gptService = mock(GptService.class);
        private final CommandPreprocessorService preprocessor = mock(CommandPreprocessorService.class);
        private final ProductRepository productRepository = mock(ProductRepository.class);
        private final LocationNamesRepository locationNamesRepository = mock(LocationNamesRepository.class);
        private final ShortTermMemoryService memoryService = mock(ShortTermMemoryService.class);
        private final FullService service = new FullService(
                whisperService,
                gptService,
                preprocessor,
                productRepository,
                locationNamesRepository,
                memoryService);

        private TestContext() {
            ReflectionTestUtils.setField(service, "objectMapper", mapper);
        }
    }
}
