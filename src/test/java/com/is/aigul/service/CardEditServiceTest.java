package com.is.aigul.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.is.aigul.dto.CommandDTO;
import com.is.aigul.dto.SellItemDTO;
import com.is.aigul.model.LocationNames;
import com.is.aigul.repository.LocationNamesRepository;
import com.is.aigul.repository.ProductRepository;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CardEditServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void editTextCardPreservesUnrequestedFields() throws Exception {
        TestContext context = new TestContext();
        CommandDTO original = moveCard();

        ObjectNode editedCard = mapper.valueToTree(original);
        editedCard.put("quantity", 10);
        editedCard.put("productName", "Silk");
        ObjectNode response = editResponse(editedCard, List.of("quantity"), List.of());

        when(context.gptService.editCommandCard(eq("make quantity 10"), any(CommandDTO.class),
                eq(List.of("Atlas")), eq(List.of("Osh")), isNull()))
                .thenReturn(response);
        when(context.preprocessor.preprocess(any(CommandDTO.class), eq(7L)))
                .thenAnswer(invocation -> {
                    CommandDTO processed = invocation.getArgument(0);
                    processed.setToLocation("Changed by preprocessor");
                    return processed;
                });

        CommandDTO result = context.service.editTextCard("make quantity 10", original, 7L);

        assertThat(result.getQuantity()).isEqualTo(10);
        assertThat(result.getProductName()).isEqualTo("Atlas");
        assertThat(result.getFromLocation()).isEqualTo("Osh");
        assertThat(result.getToLocation()).isEqualTo("Bishkek");
    }

    @Test
    void editTextCardAllowsExplicitOperationChange() throws Exception {
        TestContext context = new TestContext();
        CommandDTO original = moveCard();
        SellItemDTO item = new SellItemDTO();
        item.setProductName("Atlas");
        item.setQuantity(5);
        item.setLocation("Osh");

        ObjectNode editedCard = mapper.valueToTree(original);
        editedCard.put("operation", "ADD");
        editedCard.set("items", mapper.valueToTree(List.of(item)));
        ObjectNode response = editResponse(editedCard, List.of("operation", "items"), List.of());

        when(context.gptService.editCommandCard(anyString(), any(CommandDTO.class), anyList(), anyList(), isNull()))
                .thenReturn(response);
        when(context.preprocessor.preprocess(any(CommandDTO.class), eq(7L)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CommandDTO result = context.service.editTextCard("make this an add card", original, 7L);

        assertThat(result.getOperation()).isEqualTo("ADD");
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getProductName()).isEqualTo("Atlas");
    }

    @Test
    void quantityOnlyEditOnAddCardPreservesProductAndLocation() throws Exception {
        TestContext context = new TestContext();
        CommandDTO original = addCard("Atlass", "New Warehouse", 5);

        CommandDTO preprocessed = addCard("Atlas", "Osh", 10);
        ObjectNode editedCard = mapper.valueToTree(original);
        editedCard.set("items", mapper.valueToTree(addCard("Atlass", "New Warehouse", 10).getItems()));
        ObjectNode response = editResponse(editedCard, List.of("items[0].quantity"), List.of());

        when(context.gptService.editCommandCard(eq("make it 10"), any(CommandDTO.class),
                eq(List.of("Atlas")), eq(List.of("Osh")), isNull()))
                .thenReturn(response);
        when(context.preprocessor.preprocess(any(CommandDTO.class), eq(7L)))
                .thenReturn(preprocessed);

        CommandDTO result = context.service.editTextCard("make it 10", original, 7L);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getQuantity()).isEqualTo(10);
        assertThat(result.getItems().get(0).getProductName()).isEqualTo("Atlass");
        assertThat(result.getItems().get(0).getLocation()).isEqualTo("New Warehouse");
    }

    @Test
    void additiveQuantityEditKeepsSingleUpdatedItem() throws Exception {
        TestContext context = new TestContext();
        CommandDTO original = addCard("Atlas", "Osh", 3);
        original.getItems().get(0).setUnit("pieces");

        CommandDTO modelEdit = addCard("Atlas", "Osh", 999);
        modelEdit.getItems().get(0).setUnit("pieces");
        ObjectNode response = editResponse(
                mapper.valueToTree(modelEdit),
                List.of("items[0].quantity"),
                List.of());
        response.set("itemEdits", mapper.valueToTree(List.of(incrementEdit("Atlas", 5, List.of()))));

        when(context.gptService.editCommandCard(eq("add 5 pieces of atlas"), any(CommandDTO.class),
                eq(List.of("Atlas")), eq(List.of("Osh")), isNull()))
                .thenReturn(response);
        when(context.preprocessor.preprocess(any(CommandDTO.class), eq(7L)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CommandDTO result = context.service.editTextCard("add 5 pieces of atlas", original, 7L);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getProductName()).isEqualTo("Atlas");
        assertThat(result.getItems().get(0).getQuantity()).isEqualTo(8);
        assertThat(result.getItems().get(0).getUnit()).isEqualTo("pieces");
    }

    @Test
    void ambiguousIncrementReturnsOriginalCardWithWarning() throws Exception {
        TestContext context = new TestContext();
        CommandDTO original = addCard(List.of(item("Atlas", "Osh", 100), item("Atlas", "Bishkek", 20)));
        ObjectNode response = editResponse(mapper.valueToTree(addCard("Atlas", "Osh", 150)), List.of("items"), List.of());
        response.set("itemEdits", mapper.valueToTree(List.of(incrementEdit("Atlas", 50, List.of()))));
        when(context.gptService.editCommandCard(anyString(), any(), anyList(), anyList(), isNull())).thenReturn(response);

        CommandDTO result = context.service.editTextCard("Atlas 50", original, 7L);

        assertThat(result.getItems()).extracting(SellItemDTO::getQuantity).containsExactly(100, 20);
        assertThat(result.getWarnings()).singleElement().asString().contains("More than one");
        verifyNoInteractions(context.preprocessor);
    }

    @Test
    void explicitLocationSelectsOneIncrementTarget() throws Exception {
        TestContext context = new TestContext();
        CommandDTO original = addCard(List.of(item("Atlas", "Osh", 100), item("Atlas", "Bishkek", 20)));
        ObjectNode response = editResponse(mapper.valueToTree(original), List.of("items"), List.of());
        java.util.Map<String, Object> edit = incrementEdit("Atlas", 50, List.of("location"));
        edit.put("location", "Osh");
        response.set("itemEdits", mapper.valueToTree(List.of(edit)));
        when(context.gptService.editCommandCard(anyString(), any(), anyList(), anyList(), isNull())).thenReturn(response);
        when(context.preprocessor.preprocess(any(CommandDTO.class), eq(7L))).thenAnswer(i -> i.getArgument(0));

        CommandDTO result = context.service.editTextCard("Atlas 50 Osh", original, 7L);

        assertThat(result.getItems()).extracting(SellItemDTO::getQuantity).containsExactly(150, 20);
    }

    @Test
    void sellCardIncrementUsesOriginalQuantityInsteadOfModelTotal() throws Exception {
        TestContext context = new TestContext();
        CommandDTO original = sellCard(List.of(item("Atlas", "Osh", 100)));
        CommandDTO generated = sellCard(List.of(item("Atlas", "Osh", 50)));
        ObjectNode response = editResponse(mapper.valueToTree(generated), List.of("items"), List.of());
        response.set("itemEdits", mapper.valueToTree(List.of(incrementEdit("Atlas", 50, List.of()))));
        when(context.gptService.editCommandCard(anyString(), any(), anyList(), anyList(), isNull())).thenReturn(response);
        when(context.preprocessor.preprocess(any(CommandDTO.class), eq(7L))).thenAnswer(i -> i.getArgument(0));

        CommandDTO result = context.service.editTextCard("Atlas 50", original, 7L);

        assertThat(result.getOperation()).isEqualTo("SELL");
        assertThat(result.getItems()).extracting(SellItemDTO::getQuantity).containsExactly(150);
    }

    @Test
    void productEditOnAddCardAllowsFuzzyProductCorrectionOnly() throws Exception {
        TestContext context = new TestContext();
        CommandDTO original = addCard("Silk", "New Warehouse", 5);

        CommandDTO preprocessed = addCard("Atlas", "Osh", 5);
        ObjectNode editedCard = mapper.valueToTree(original);
        editedCard.set("items", mapper.valueToTree(addCard("Atlass", "New Warehouse", 5).getItems()));
        ObjectNode response = editResponse(editedCard, List.of("items[0].productName"), List.of());

        when(context.gptService.editCommandCard(eq("change product to atlas"), any(CommandDTO.class),
                eq(List.of("Atlas")), eq(List.of("Osh")), isNull()))
                .thenReturn(response);
        when(context.preprocessor.preprocess(any(CommandDTO.class), eq(7L)))
                .thenReturn(preprocessed);

        CommandDTO result = context.service.editTextCard("change product to atlas", original, 7L);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getProductName()).isEqualTo("Atlas");
        assertThat(result.getItems().get(0).getLocation()).isEqualTo("New Warehouse");
        assertThat(result.getItems().get(0).getQuantity()).isEqualTo(5);
    }

    @Test
    void itemOnlyEditAppendsInsteadOfReplacingExistingItems() throws Exception {
        TestContext context = new TestContext();
        CommandDTO original = addCard(List.of(
                item("Atlas", "Osh", 5),
                item("Silk", "Bishkek", 2)));
        CommandDTO modelEdit = addCard("Cotton", "Osh", 3);
        ObjectNode response = editResponse(mapper.valueToTree(modelEdit), List.of("items"), List.of());

        when(context.gptService.editCommandCard(eq("cotton 3 osh"), any(CommandDTO.class),
                eq(List.of("Atlas")), eq(List.of("Osh")), isNull()))
                .thenReturn(response);
        when(context.preprocessor.preprocess(any(CommandDTO.class), eq(7L)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CommandDTO result = context.service.editTextCard("cotton 3 osh", original, 7L);

        assertThat(result.getItems()).hasSize(3);
        assertThat(result.getItems()).extracting(SellItemDTO::getProductName)
                .containsExactly("Atlas", "Silk", "Cotton");
        assertThat(result.getItems().get(2).getQuantity()).isEqualTo(3);
    }

    @Test
    void itemOnlyEditDoesNotReplaceSimilarExistingProductName() throws Exception {
        TestContext context = new TestContext();
        CommandDTO original = addCard("Atlas", "Osh", 5);
        CommandDTO modelEdit = addCard("Atlas Big", "Osh", 3);
        ObjectNode response = editResponse(mapper.valueToTree(modelEdit), List.of("items"), List.of());

        when(context.gptService.editCommandCard(eq("add atlas big to the list"), any(CommandDTO.class),
                eq(List.of("Atlas")), eq(List.of("Osh")), isNull()))
                .thenReturn(response);
        when(context.preprocessor.preprocess(any(CommandDTO.class), eq(7L)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CommandDTO result = context.service.editTextCard("add atlas big to the list", original, 7L);

        assertThat(result.getItems()).hasSize(2);
        assertThat(result.getItems()).extracting(SellItemDTO::getProductName)
                .containsExactly("Atlas", "Atlas Big");
        assertThat(result.getItems()).extracting(SellItemDTO::getQuantity)
                .containsExactly(5, 3);
    }

    @Test
    void fullModelResponseDoesNotDuplicateAlteredCopiesOfExistingItems() throws Exception {
        TestContext context = new TestContext();
        SellItemDTO atlas = item("Atlas", "Osh", 5);
        atlas.setFinalPrice(100.0);
        SellItemDTO silk = item("Silk", "Bishkek", 2);
        CommandDTO original = addCard(List.of(atlas, silk));

        SellItemDTO alteredAtlas = item(" atlas ", "OSH", 5);
        alteredAtlas.setFinalPrice(95.0);
        alteredAtlas.setTotalPrice(475.0);
        alteredAtlas.setCalculationSteps("recalculated");
        SellItemDTO alteredSilk = item("Silk", "Bishkek", 2);
        alteredSilk.setFinalPrice(50.0);
        SellItemDTO cotton = item("Cotton", "Osh", 3);
        ObjectNode response = editResponse(mapper.valueToTree(addCard(List.of(alteredAtlas, alteredSilk, cotton))),
                List.of("items"), List.of());

        when(context.gptService.editCommandCard(anyString(), any(CommandDTO.class), anyList(), anyList(), isNull()))
                .thenReturn(response);
        when(context.preprocessor.preprocess(any(CommandDTO.class), eq(7L)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CommandDTO result = context.service.editTextCard("add cotton 3", original, 7L);

        assertThat(result.getItems()).hasSize(3);
        assertThat(result.getItems()).extracting(SellItemDTO::getProductName)
                .containsExactly("Atlas", "Silk", "Cotton");
        assertThat(result.getItems().get(0).getFinalPrice()).isEqualTo(100.0);
    }

    @Test
    void preprocessingChangesDoNotTriggerASecondItemMerge() throws Exception {
        TestContext context = new TestContext();
        CommandDTO original = addCard("Atlass", "osh ", 5);
        CommandDTO modelEdit = addCard(List.of(item("Atlass", "osh ", 5), item("Cotton", "Osh", 3)));
        ObjectNode response = editResponse(mapper.valueToTree(modelEdit), List.of("items"), List.of());

        when(context.gptService.editCommandCard(anyString(), any(CommandDTO.class), anyList(), anyList(), isNull()))
                .thenReturn(response);
        when(context.preprocessor.preprocess(any(CommandDTO.class), eq(7L)))
                .thenAnswer(invocation -> {
                    CommandDTO processed = invocation.getArgument(0);
                    processed.getItems().get(0).setProductName("Atlas");
                    processed.getItems().get(0).setLocation("Osh");
                    processed.getItems().get(0).setFinalPrice(100.0);
                    return processed;
                });

        CommandDTO result = context.service.editTextCard("add cotton 3", original, 7L);

        assertThat(result.getItems()).hasSize(2);
        assertThat(result.getItems()).extracting(SellItemDTO::getProductName)
                .containsExactly("Atlas", "Cotton");
    }

    @Test
    void identicalAdditionalOccurrenceIsAppendedOneToOne() throws Exception {
        TestContext context = new TestContext();
        SellItemDTO atlas = item("Atlas", "Osh", 5);
        CommandDTO original = addCard(atlas.getProductName(), atlas.getLocation(), atlas.getQuantity());
        CommandDTO modelEdit = addCard(List.of(item("Atlas", "Osh", 5), item("Atlas", "Osh", 5)));
        ObjectNode response = editResponse(mapper.valueToTree(modelEdit), List.of("items"), List.of());

        when(context.gptService.editCommandCard(anyString(), any(CommandDTO.class), anyList(), anyList(), isNull()))
                .thenReturn(response);
        when(context.preprocessor.preprocess(any(CommandDTO.class), eq(7L)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CommandDTO result = context.service.editTextCard("add another identical atlas row", original, 7L);

        assertThat(result.getItems()).hasSize(2);
        assertThat(result.getItems()).extracting(SellItemDTO::getQuantity).containsExactly(5, 5);
    }

    @Test
    void sameProductWithDifferentQuantityOrLocationRemainsSeparate() throws Exception {
        TestContext context = new TestContext();
        CommandDTO original = addCard("Atlas", "Osh", 5);
        CommandDTO modelEdit = addCard(List.of(
                item("Atlas", "Osh", 5),
                item("Atlas", "Osh", 3),
                item("Atlas", "Bishkek", 5)));
        ObjectNode response = editResponse(mapper.valueToTree(modelEdit), List.of("items"), List.of());

        when(context.gptService.editCommandCard(anyString(), any(CommandDTO.class), anyList(), anyList(), isNull()))
                .thenReturn(response);
        when(context.preprocessor.preprocess(any(CommandDTO.class), eq(7L)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CommandDTO result = context.service.editTextCard("add atlas 3 and atlas 5 bishkek", original, 7L);

        assertThat(result.getItems()).hasSize(3);
        assertThat(result.getItems()).extracting(SellItemDTO::getQuantity).containsExactly(5, 3, 5);
        assertThat(result.getItems()).extracting(SellItemDTO::getLocation)
                .containsExactly("Osh", "Osh", "Bishkek");
    }

    @Test
    void sameProductWithDifferentUnitPriceOrDiscountRemainsSeparate() throws Exception {
        TestContext context = new TestContext();
        SellItemDTO originalAtlas = item("Atlas", "Osh", 5);
        originalAtlas.setUnit("PIECE");
        originalAtlas.setOriginalPrice(100.0);
        originalAtlas.setDiscount(0.0);
        SellItemDTO newAtlas = item("Atlas", "Osh", 5);
        newAtlas.setUnit("PACK");
        newAtlas.setOriginalPrice(90.0);
        newAtlas.setDiscount(5.0);
        CommandDTO original = addCard(List.of(originalAtlas));
        ObjectNode response = editResponse(mapper.valueToTree(addCard(List.of(originalAtlas, newAtlas))),
                List.of("items"), List.of());
        response.set("itemEdits", mapper.valueToTree(List.of(java.util.Map.of("mode", "APPEND"))));
        when(context.gptService.editCommandCard(anyString(), any(), anyList(), anyList(), isNull())).thenReturn(response);
        when(context.preprocessor.preprocess(any(CommandDTO.class), eq(7L))).thenAnswer(i -> i.getArgument(0));

        CommandDTO result = context.service.editTextCard("new row Atlas 5 packs price 90 discount 5", original, 7L);

        assertThat(result.getItems()).hasSize(2);
        assertThat(result.getItems()).extracting(SellItemDTO::getUnit).containsExactly("PIECE", "PACK");
        assertThat(result.getItems()).extracting(SellItemDTO::getOriginalPrice).containsExactly(100.0, 90.0);
        assertThat(result.getItems()).extracting(SellItemDTO::getDiscount).containsExactly(0.0, 5.0);
    }

    @Test
    void explicitReplacementCanReplaceWholeItemsList() throws Exception {
        TestContext context = new TestContext();
        CommandDTO original = addCard(List.of(
                item("Atlas", "Osh", 5),
                item("Silk", "Bishkek", 2)));
        CommandDTO modelEdit = addCard("Cotton", "Osh", 3);
        ObjectNode response = editResponse(mapper.valueToTree(modelEdit), List.of("items"), List.of());

        when(context.gptService.editCommandCard(eq("replace everything with cotton 3 osh"), any(CommandDTO.class),
                eq(List.of("Atlas")), eq(List.of("Osh")), isNull()))
                .thenReturn(response);
        when(context.preprocessor.preprocess(any(CommandDTO.class), eq(7L)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CommandDTO result = context.service.editTextCard("replace everything with cotton 3 osh", original, 7L);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getProductName()).isEqualTo("Cotton");
        assertThat(result.getItems().get(0).getQuantity()).isEqualTo(3);
    }


    @Test
    void editTextCardReturnsOriginalWithWarningForAmbiguousEdit() throws Exception {
        TestContext context = new TestContext();
        CommandDTO original = moveCard();
        ObjectNode response = editResponse(mapper.valueToTree(original), List.of(), List.of("Which location should change?"));

        when(context.gptService.editCommandCard(anyString(), any(CommandDTO.class), anyList(), anyList(), isNull()))
                .thenReturn(response);
        when(context.preprocessor.preprocess(any(CommandDTO.class), eq(7L)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CommandDTO result = context.service.editTextCard("change location", original, 7L);

        assertThat(result.getOperation()).isEqualTo(original.getOperation());
        assertThat(result.getQuantity()).isEqualTo(original.getQuantity());
        assertThat(result.getWarnings()).containsExactly("Which location should change?");
    }

    @Test
    void editAudioCardTranscribesOnceAndUsesSameEditPath() throws Exception {
        TestContext context = new TestContext();
        CommandDTO original = moveCard();
        TranscriptionResult transcription = new TranscriptionResult("make quantity 3", "en", 0.9, List.of());
        File audio = Files.createTempFile("aigul-card-edit", ".m4a").toFile();

        ObjectNode editedCard = mapper.valueToTree(original);
        editedCard.put("quantity", 3);
        ObjectNode response = editResponse(editedCard, List.of("quantity"), List.of());

        when(context.whisperService.transcribe(eq(audio), eq(List.of("Atlas")), eq(List.of("Osh"))))
                .thenReturn(transcription);
        when(context.gptService.editCommandCard(eq("make quantity 3"), any(CommandDTO.class),
                eq(List.of("Atlas")), eq(List.of("Osh")), eq(transcription)))
                .thenReturn(response);
        when(context.preprocessor.preprocess(any(CommandDTO.class), eq(7L)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        try {
            CommandDTO result = context.service.editAudioCard(audio, original, 7L);

            assertThat(result.getQuantity()).isEqualTo(3);
            verify(context.whisperService, times(1)).transcribe(audio, List.of("Atlas"), List.of("Osh"));
            verify(context.gptService, times(1)).editCommandCard("make quantity 3", original,
                    List.of("Atlas"), List.of("Osh"), transcription);
        } finally {
            Files.deleteIfExists(audio.toPath());
        }
    }

    @Test
    void editTextCardRejectsBlankInstruction() {
        TestContext context = new TestContext();

        assertThatThrownBy(() -> context.service.editTextCard(" ", moveCard(), 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Edit instruction");
    }

    private ObjectNode editResponse(ObjectNode card, List<String> changedFields, List<String> warnings) {
        ObjectNode response = mapper.createObjectNode();
        response.set("card", card);
        response.set("changedFields", mapper.valueToTree(changedFields));
        response.set("warnings", mapper.valueToTree(warnings));
        response.set("itemEdits", mapper.createArrayNode());
        return response;
    }

    private java.util.Map<String, Object> incrementEdit(String product, int delta, List<String> explicitFields) {
        java.util.Map<String, Object> edit = new java.util.HashMap<>();
        edit.put("mode", "INCREMENT");
        edit.put("quantityDelta", delta);
        edit.put("productName", product);
        edit.put("explicitFields", explicitFields);
        return edit;
    }

    private CommandDTO moveCard() {
        CommandDTO card = new CommandDTO();
        card.setOperation("MOVE");
        card.setProductName("Atlas");
        card.setQuantity(5);
        card.setFromLocation("Osh");
        card.setToLocation("Bishkek");
        return card;
    }

    private CommandDTO addCard(String productName, String location, int quantity) {
        return addCard(List.of(item(productName, location, quantity)));
    }

    private CommandDTO addCard(List<SellItemDTO> items) {
        CommandDTO card = new CommandDTO();
        card.setOperation("ADD");
        card.setItems(items);
        return card;
    }

    private CommandDTO sellCard(List<SellItemDTO> items) {
        CommandDTO card = new CommandDTO();
        card.setOperation("SELL");
        card.setItems(items);
        return card;
    }

    private SellItemDTO item(String productName, String location, int quantity) {
        SellItemDTO item = new SellItemDTO();
        item.setProductName(productName);
        item.setLocation(location);
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
        private final CardEditService service = new CardEditService(
                whisperService,
                gptService,
                preprocessor,
                productRepository,
                locationNamesRepository,
                memoryService,
                mapper);

        private TestContext() {
            LocationNames location = new LocationNames();
            location.setLocationName("Osh");
            when(productRepository.findAllNamesByUserId(7L)).thenReturn(List.of("Atlas"));
            when(locationNamesRepository.findByUserId(7L)).thenReturn(List.of(location));
        }
    }
}
