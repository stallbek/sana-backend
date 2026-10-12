package com.is.aigul.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.is.aigul.dto.CommandDTO;
import com.is.aigul.model.LocationNames;
import com.is.aigul.repository.LocationNamesRepository;
import com.is.aigul.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.HashSet;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CardEditService {

    private static final Set<String> ALWAYS_ALLOWED_POSTPROCESS_FIELDS = Set.of("warnings");

    private final WhisperService whisperService;
    private final GptService gptService;
    private final CommandPreprocessorService commandPreprocessorService;
    private final ProductRepository productRepository;
    private final LocationNamesRepository locationNamesRepository;
    private final ShortTermMemoryService shortTermMemoryService;
    private final ObjectMapper objectMapper;

    public CommandDTO editTextCard(String text, CommandDTO card, Long userId) {
        validateInputs(text, card);
        List<String> knownProducts = productRepository.findAllNamesByUserId(userId);
        List<String> knownLocations = getKnownLocations(userId);
        return editCard(text.trim(), card, userId, knownProducts, knownLocations, null);
    }

    public CommandDTO editAudioCard(File audioFile, CommandDTO card, Long userId) {
        if (audioFile == null) {
            throw new IllegalArgumentException("Audio file is required.");
        }
        if (card == null) {
            throw new IllegalArgumentException("Command card is required.");
        }

        List<String> knownProducts = productRepository.findAllNamesByUserId(userId);
        List<String> knownLocations = getKnownLocations(userId);
        try {
            TranscriptionResult transcription = whisperService.transcribe(audioFile, knownProducts, knownLocations);
            String text = transcription.text();
            validateInputs(text, card);
            return editCard(text.trim(), card, userId, knownProducts, knownLocations, transcription);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Error during audio card edit: " + e.getMessage(), e);
        }
    }

    private CommandDTO editCard(String text, CommandDTO card, Long userId, List<String> knownProducts,
            List<String> knownLocations, TranscriptionResult transcription) {
        try {
            JsonNode response = gptService.editCommandCard(text, card, knownProducts, knownLocations, transcription);
            JsonNode editedCard = response.get("card");
            if (editedCard == null || !editedCard.isObject()) {
                throw new RuntimeException("Invalid response from GPT: 'card' object missing.");
            }

            Set<String> changedFields = readChangedFields(response.get("changedFields"));
            JsonNode warnings = response.get("warnings");
            if (warnings != null && warnings.isArray() && !warnings.isEmpty()) {
                ((ObjectNode) editedCard).set("warnings", warnings);
                changedFields.add("warnings");
            }
            if (changedFields.isEmpty() && !objectMapper.valueToTree(card).equals(editedCard)) {
                throw new RuntimeException("Invalid response from GPT: changedFields missing for edited card.");
            }

            ObjectNode originalJson = objectMapper.valueToTree(card);
            ObjectNode guardedJson = preserveUnrequestedFields(originalJson, (ObjectNode) editedCard, changedFields);
            boolean structuredItemEdit = applyStructuredItemEdits(originalJson, guardedJson,
                    response.path("itemEdits"), changedFields);
            if (!structuredItemEdit) {
                preventImplicitItemListReplacement(text, originalJson, guardedJson, changedFields);
            }

            if (structuredItemEdit && changedFields.equals(Set.of("warnings"))) {
                CommandDTO ambiguousResult = objectMapper.treeToValue(guardedJson, CommandDTO.class);
                shortTermMemoryService.rememberPendingCommands(userId, text, List.of(ambiguousResult));
                return ambiguousResult;
            }

            CommandDTO guardedCard = objectMapper.treeToValue(guardedJson, CommandDTO.class);
            CommandDTO processedCard = commandPreprocessorService.preprocess(guardedCard, userId);
            if (processedCard == null) {
                return guardedCard;
            }

            Set<String> postprocessFields = new HashSet<>(changedFields);
            postprocessFields.addAll(ALWAYS_ALLOWED_POSTPROCESS_FIELDS);
            ObjectNode processedJson = objectMapper.valueToTree(processedCard);
            ObjectNode finalJson = preserveUnrequestedFields(originalJson, processedJson, postprocessFields);
            CommandDTO result = objectMapper.treeToValue(finalJson, CommandDTO.class);
            shortTermMemoryService.rememberPendingCommands(userId, text, List.of(result));
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Error during command card edit: " + e.getMessage(), e);
        }
    }

    private void validateInputs(String text, CommandDTO card) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Edit instruction is required.");
        }
        if (card == null) {
            throw new IllegalArgumentException("Command card is required.");
        }
        if (card.getOperation() == null || card.getOperation().isBlank()) {
            throw new IllegalArgumentException("Command card operation is required.");
        }
    }

    private List<String> getKnownLocations(Long userId) {
        return locationNamesRepository.findByUserId(userId)
                .stream()
                .map(LocationNames::getLocationName)
                .toList();
    }

    private Set<String> readChangedFields(JsonNode changedFieldsNode) {
        Set<String> changedFields = new HashSet<>();
        if (changedFieldsNode == null || !changedFieldsNode.isArray()) {
            return changedFields;
        }

        for (JsonNode field : changedFieldsNode) {
            if (field.isTextual() && !field.asText().isBlank()) {
                changedFields.add(field.asText().trim());
            }
        }
        return changedFields;
    }

    private ObjectNode preserveUnrequestedFields(ObjectNode original, ObjectNode edited, Set<String> changedFields) {
        ObjectNode result = edited.deepCopy();

        original.propertyNames().forEach(field -> {
            if (!isFieldChanged(field, changedFields)) {
                result.set(field, original.get(field));
            }
        });

        preserveUnrequestedItemFields(original, result, changedFields);

        List<String> extraFields = new java.util.ArrayList<>();
        result.propertyNames().forEach(field -> {
            if (!isFieldChanged(field, changedFields) && !original.has(field)) {
                extraFields.add(field);
            }
        });
        extraFields.forEach(result::remove);

        return result;
    }

    private boolean isFieldChanged(String field, Set<String> changedFields) {
        if (changedFields.contains(field)) {
            return true;
        }
        String dotPrefix = field + ".";
        String bracketPrefix = field + "[";
        return changedFields.stream()
                .anyMatch(changedField -> changedField.startsWith(dotPrefix)
                        || changedField.startsWith(bracketPrefix));
    }

    private void preserveUnrequestedItemFields(ObjectNode original, ObjectNode result, Set<String> changedFields) {
        if (changedFields.contains("items")
                || !isFieldChanged("items", changedFields)
                || !original.path("items").isArray()
                || !result.path("items").isArray()) {
            return;
        }

        for (int i = 0; i < result.path("items").size() && i < original.path("items").size(); i++) {
            JsonNode originalItemNode = original.path("items").get(i);
            JsonNode resultItemNode = result.path("items").get(i);
            if (!originalItemNode.isObject() || !resultItemNode.isObject()) {
                continue;
            }

            ObjectNode originalItem = (ObjectNode) originalItemNode;
            ObjectNode resultItem = (ObjectNode) resultItemNode;
            int itemIndex = i;
            originalItem.propertyNames().forEach(field -> {
                if (!isItemFieldChanged(itemIndex, field, changedFields)) {
                    resultItem.set(field, originalItem.get(field));
                }
            });
        }
    }

    private boolean isItemFieldChanged(int itemIndex, String field, Set<String> changedFields) {
        String exactIndexedPath = "items[" + itemIndex + "]." + field;
        String allItemsPath = "items." + field;
        return changedFields.contains(exactIndexedPath) || changedFields.contains(allItemsPath);
    }

    private void preventImplicitItemListReplacement(String text, ObjectNode original, ObjectNode result,
            Set<String> changedFields) {
        if (!changedFields.contains("items")
                || hasExplicitItemReplacementIntent(text)
                || !original.path("items").isArray()
                || !result.path("items").isArray()) {
            return;
        }

        JsonNode originalItems = original.path("items");
        JsonNode editedItems = result.path("items");
        boolean[] consumedOriginalItems = new boolean[originalItems.size()];
        ArrayNode mergedItems = objectMapper.createArrayNode();
        originalItems.forEach(mergedItems::add);
        editedItems.forEach(item -> {
            int matchingOriginalIndex = findUnconsumedMatchingItem(originalItems, item, consumedOriginalItems);
            if (matchingOriginalIndex >= 0) {
                consumedOriginalItems[matchingOriginalIndex] = true;
            } else {
                mergedItems.add(item);
            }
        });
        result.set("items", mergedItems);
    }

    private boolean applyStructuredItemEdits(ObjectNode original, ObjectNode result, JsonNode itemEdits,
            Set<String> changedFields) {
        if (!itemEdits.isArray() || itemEdits.isEmpty()
                || !original.path("items").isArray() || !result.path("items").isArray()) {
            return false;
        }

        boolean handledIncrement = false;
        ArrayNode deterministicItems = (ArrayNode) original.path("items").deepCopy();
        for (JsonNode edit : itemEdits) {
            if (!"INCREMENT".equalsIgnoreCase(edit.path("mode").asText())) {
                continue;
            }
            handledIncrement = true;
            List<Integer> matches = findMatchingOriginalRows(original.path("items"), edit);
            if (matches.size() != 1 || !edit.path("quantityDelta").isIntegralNumber()
                    || edit.path("quantityDelta").asLong() <= 0) {
                result.set("items", original.path("items").deepCopy());
                ArrayNode warnings = original.path("warnings").isArray()
                        ? (ArrayNode) original.path("warnings").deepCopy()
                        : objectMapper.createArrayNode();
                warnings.add(matches.isEmpty()
                        ? "Could not identify one existing item row to increment. Please specify the row details."
                        : "More than one item row matches this increment. Please specify location, unit, price, or discount.");
                result.set("warnings", warnings);
                changedFields.clear();
                changedFields.add("warnings");
                return true;
            }

            ObjectNode target = (ObjectNode) deterministicItems.get(matches.get(0));
            BigDecimal originalQuantity = target.path("quantity").decimalValue();
            BigDecimal newQuantity = originalQuantity.add(edit.path("quantityDelta").decimalValue());
            target.set("quantity", objectMapper.getNodeFactory().numberNode(newQuantity));
            changedFields.add("items");
        }
        if (handledIncrement) {
            result.set("items", deterministicItems);
        }
        return handledIncrement;
    }

    private List<Integer> findMatchingOriginalRows(JsonNode originalItems, JsonNode edit) {
        List<Integer> matches = new ArrayList<>();
        Set<String> explicitFields = readChangedFields(edit.path("explicitFields"));
        for (int i = 0; i < originalItems.size(); i++) {
            JsonNode item = originalItems.get(i);
            if (!normalizedText(item, "productName").equals(normalizedValue(edit.path("productName")))) {
                continue;
            }
            if (matchesExplicitField(item, edit, explicitFields, "location")
                    && matchesExplicitField(item, edit, explicitFields, "unit")
                    && matchesExplicitNumber(item, edit, explicitFields, "originalPrice")
                    && matchesExplicitNumber(item, edit, explicitFields, "discount")) {
                matches.add(i);
            }
        }
        return matches;
    }

    private boolean matchesExplicitField(JsonNode item, JsonNode edit, Set<String> explicitFields, String field) {
        return !explicitFields.contains(field)
                || normalizedText(item, field).equals(normalizedValue(edit.path(field)));
    }

    private boolean matchesExplicitNumber(JsonNode item, JsonNode edit, Set<String> explicitFields, String field) {
        if (!explicitFields.contains(field)) {
            return true;
        }
        JsonNode left = item.path(field);
        JsonNode right = edit.path(field);
        return left.isNumber() && right.isNumber() && left.decimalValue().compareTo(right.decimalValue()) == 0;
    }

    private String normalizedValue(JsonNode value) {
        return value.isMissingNode() || value.isNull() ? "" : value.asText().trim().toLowerCase(Locale.ROOT);
    }

    private int findUnconsumedMatchingItem(JsonNode originalItems, JsonNode candidate,
            boolean[] consumedOriginalItems) {
        for (int i = 0; i < originalItems.size(); i++) {
            if (!consumedOriginalItems[i] && hasSameStableItemIdentity(originalItems.get(i), candidate)) {
                return i;
            }
        }
        return -1;
    }

    private boolean hasSameStableItemIdentity(JsonNode left, JsonNode right) {
        return normalizedText(left, "productName").equals(normalizedText(right, "productName"))
                && normalizedText(left, "location").equals(normalizedText(right, "location"))
                && normalizedText(left, "unit").equals(normalizedText(right, "unit"))
                && normalizedNumber(left, "originalPrice").equals(normalizedNumber(right, "originalPrice"))
                && normalizedNumber(left, "discount").equals(normalizedNumber(right, "discount"))
                && normalizedQuantity(left).equals(normalizedQuantity(right));
    }

    private String normalizedNumber(JsonNode item, String field) {
        JsonNode value = item.path(field);
        if (value.isNumber()) {
            return value.decimalValue().stripTrailingZeros().toPlainString();
        }
        return value.isMissingNode() || value.isNull() ? "" : value.asText().trim();
    }

    private String normalizedText(JsonNode item, String field) {
        JsonNode value = item.path(field);
        return value.isMissingNode() || value.isNull()
                ? ""
                : value.asText().trim().toLowerCase(Locale.ROOT);
    }

    private String normalizedQuantity(JsonNode item) {
        JsonNode quantity = item.path("quantity");
        if (quantity.isNumber()) {
            return quantity.decimalValue().stripTrailingZeros().toPlainString();
        }
        return quantity.isMissingNode() || quantity.isNull() ? "" : quantity.asText().trim();
    }

    private boolean hasExplicitItemReplacementIntent(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("remove")
                || normalized.contains("delete")
                || normalized.contains("replace")
                || normalized.contains("substitute")
                || normalized.contains("clear")
                || normalized.contains("only")
                || normalized.contains("everything else")
                || normalized.contains("instead")
                || normalized.contains("убери")
                || normalized.contains("удали")
                || normalized.contains("замени")
                || normalized.contains("только")
                || normalized.contains("вместо")
                || normalized.contains("алып сал")
                || normalized.contains("өчүр")
                || normalized.contains("алмаштыр")
                || normalized.contains("гана");
    }

}
