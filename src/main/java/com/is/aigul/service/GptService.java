package com.is.aigul.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.is.aigul.dto.CommandDTO;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class GptService {

    private static final Logger log = LoggerFactory.getLogger(GptService.class);
    @Value("${ai.provider:deepseek}")
    private String provider;
    @Value("${deepseek.apikey:}")
    private String deepSeekApiKey;
    @Value("${deepseek.model:deepseek-v4-flash}")
    private String deepSeekModel;
    @Value("${deepseek.url:https://api.deepseek.com/chat/completions}")
    private String deepSeekUrl;
    @Value("${deepseek.thinking-enabled:false}")
    private boolean deepSeekThinkingEnabled;
    @Value("${openai.apikey:}")
    private String openAiApiKey;
    @Value("${openai.model:gpt-5.4}")
    private String openAiModel;
    @Value("${openai.url:https://api.openai.com/v1/responses}")
    private String openAiUrl;
    @Value("${openai.reasoning-effort:low}")
    private String openAiReasoningEffort;

    private static final OkHttpClient client = new OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .connectionPool(new ConnectionPool(5, 2, TimeUnit.MINUTES))
            .build();

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            You convert natural language inventory instructions into a single JSON command.

            DATA MODEL: Products have unique names, each with multiple ProductLocation records (locationName, quantity).
            Default location backend handles it when location is "").

            JSON SCHEMA:
            {
              "commands": [
                {
                  "operation": "CREATE|ADD|SELL|MOVE|GET|UPDATE|DELETE",
                  "productName": "string",
                  "quantity": int,
                  "location": "string",
                  "fromLocation": "string",
                  "toLocation": "string",
                  "updateType": "location|name|quantity|price",
                  "updatedValue": "string",
                  "originalPrice": double,
                  "discount": double,
                  "message": "string",
                  "items": [{
                    "productName": "string", "quantity": int, "location": "string",
                    "originalPrice": double, "discount": double, "totalPrice": double,
                    "calculationSteps": "string"
                  }]
                }
              ]
            }

            PRODUCT MATCHING (IMPORTANT):
            You may receive a list of KNOWN PRODUCTS that exist in the user's inventory.
            When the user mentions a product name:
            - If it clearly matches a known product (including misspellings, phonetic variations, transliterations, different language forms, different word order, or Russian grammatical declensions/plurals/diminutives), use the EXACT name from the known list. Word order does not matter: "atlas black" = "black atlas". Russian declensions must match: атласа/атласов/атласу/атласом = "атлас", шелка/шелку = "шелк".
            - If it does NOT match any known product, use the name exactly as the user said (it is a new product).
            - For CREATE: ALWAYS use the product name exactly as the user said. Never match to an existing product. The user explicitly wants a NEW product, even if the name is similar to an existing one (e.g., "black atlas" is a different product from "atlas").
            - For SELL, MOVE, UPDATE, DELETE: the product MUST exist, so always try to match to a known product.

            MULTI-COMMANDS (Card Stacks):
            If the user speaks a continuous thought containing multiple completely distinct operations (e.g., "sell 5 boxes and move 10 to Osh"), you MUST split them into separate distinct command objects inside the "commands" array.
            However, if it is a single task with multiple products (e.g., "sell 5 red threads and 2 blue threads"), simply group them under ONE "SELL" operation using the "items" array.

            RULES:
            1. Always output a root JSON object with a "commands" array containing the parsed action(s). No explanations, no text outside JSON.
            2. Support Russian, Kyrgyz, and English input.
            3. If no location mentioned, set location to "".
            4. Movement between locations -> "MOVE" with fromLocation/toLocation.
            5. Updates -> "UPDATE" with updateType and updatedValue.
            6. Prices/discounts default to 0. All numeric values must be numbers, not strings.
            7. Units (meters, packs) appear only in calculationSteps.
            8. If the user does not specify an action verb, default to "SELL" (most common merchant operation). E.g., "10 атласа" = sell 10 atlas.
            9. Convert number words to digits: двадцать=20, сто=100, пятьдесят=50, тысяча=1000, etc. E.g., "двадцать штук атласа" -> quantity: 20.
            10. Default quantities: SELL defaults to 1 if not specified. CREATE defaults to 0 if not specified. ADD requires quantity.

            CREATE vs ADD:
            - CREATE = new product that does NOT exist yet. Uses top-level fields: productName, quantity, location, originalPrice. No items array.
            - ADD = restock a product that ALREADY exists. Uses items array.
            - If user says "create"/"создать"/"түз" explicitly -> always "CREATE".
            - If user says "add"/"добавь"/"кош" and the product matches a KNOWN PRODUCT -> "ADD" with items array.
            - If user says "add"/"добавь"/"кош" but the product does NOT match any KNOWN PRODUCT and the price is mentioned -> use "CREATE" instead (it is a new product that needs to be created first).
            - For multi-item ADD where some products are new and some exist -> keep "ADD" with items array.

            SELL: operation "SELL", items array required. Each item needs: productName, quantity (default 1), location ("" if none), originalPrice (0), discount (0), totalPrice (0), calculationSteps. Multiply packs/sets for total quantity. Per-unit discount multiplied by total quantity.

            ADD: operation "ADD", items array required. Each item needs: productName, quantity, location ("" if none), originalPrice (0), discount (always 0). Multiply packs/bundles for total quantity. Never subtracts.

            MOVE: Use fromLocation and toLocation fields.

            UPDATE: updateType is one of: location, name, quantity, price. updatedValue holds new value.

            CREATE: Creates new product. Include location if mentioned, else "".

            GET: For questions/analytics about data. Set operation "GET", copy full user text to "message". No need to set productName or location. Never create/modify data.

            DRAFT CORRECTIONS vs DB UPDATES (CRITICAL):
            1. DRAFT CORRECTION (Editing a pending action card): The backend direct card editor handles pending-card corrections. In this parser, use only the current user input and do not invent a previous card from missing context.
            2. DB UPDATE (Modifying catalog data): If the user explicitly asks to permanently change a product's details in the database catalog (e.g., "Change the base price of Atlas to 500", "Rename Atlas to Big Atlas"), use the "UPDATE" operation with `updateType` and `updatedValue`.

            EXAMPLES:

            Input: "Я продаю 10 пачек атласа по 4 метра, цена 100 сом/метр, скидка 5 сом/шт"
            Output: {"commands":[{"operation":"SELL","items":[{"productName":"атлас","quantity":40,"location":"","originalPrice":100,"discount":200,"totalPrice":3800,"calculationSteps":"10 packs * 4 meters = 40 units. 40 * 100 = 4000. Discount 5 per unit = 200. Total = 3800"}]}]}

            Input: "Добавь 15 рулонов шелка в Ош"
            Output: {"commands":[{"operation":"ADD","items":[{"productName":"шелк","quantity":15,"location":"Ош","originalPrice":0,"discount":0}]}]}

            Input: "Перемести 30 рулонов ткани из Ош в Бишкек и продай 5 атласов"
            Output: {"commands":[{"operation":"MOVE","productName":"ткань","quantity":30,"fromLocation":"Ош","toLocation":"Бишкек"}, {"operation":"SELL","items":[{"productName":"атлас","quantity":5,"location":"","originalPrice":0,"discount":0,"totalPrice":0,"calculationSteps":"5 pcs"}]}]}

            Input: "Show me top selling products for last month"
            Output: {"commands":[{"operation":"GET","message":"Show me top selling products for last month"}]}
            """;

    private static final String CARD_EDIT_SYSTEM_PROMPT = """
            You edit one pending inventory command card from a merchant assistant UI.

            INPUTS:
            - The user gives an editorial instruction in Russian, Kyrgyz, English, or mixed speech.
            - You receive exactly one CURRENT_CARD JSON object.
            - You may receive known product and location names for spelling and phonetic correction.

            OUTPUT JSON ONLY:
            {
              "card": { edited CommandDTO object },
              "changedFields": ["fieldName"],
              "warnings": ["string"]
            }

            EDITING RULES:
            1. The CURRENT_CARD is the only source of truth. Do not use memory or infer another card.
            2. Read the user's intent naturally, not as fixed keywords. Merchant corrections are often short:
               "actually 12", "no, 12", "I meant 12", "instead put 12", "same but 12", "price 70",
               "make that 70", "another 10", "10 more", "not Atlas, Atlas Big". Treat equivalent
               Russian, Kyrgyz, English, or mixed wording the same way.
            3. Preserve every field that the user did not explicitly ask to change.
            4. If the user asks to change product, quantity, location, price, discount, move locations, or items, change only the relevant field(s).
            5. For bare numeric corrections on a product card:
               - If the wording means addition/restock/extra/more/also, increment the existing quantity.
               - If the wording means correction/replacement/actually/meant/instead/make/set/no, set the quantity to the new number.
               - If the wording mentions price/cost/som, update price, not quantity.
            6. Change the operation only when explicit, for example "make it ADD instead of SELL", "delete this product", or "move it instead".
            7. If operation changes, build the card for the new operation using the user's instruction and reusable values from CURRENT_CARD. Put all changed top-level fields in changedFields.
            8. For SELL and ADD cards with items, return the full updated items array, but use granular changedFields paths for item edits:
               - "items[0].quantity" for the first item's quantity
               - "items[1].productName" for the second item's product
               - "items.location" only when the same location edit applies to every item
               Use "items" only when adding, removing, replacing, or reordering whole items.
            9. For SELL and ADD item edits, also return itemEdits (internal enforcement metadata):
               [{"mode":"INCREMENT|APPEND|REPLACE|REMOVE","quantityDelta":number,"productName":"string",
                 "location":"string","unit":"string","originalPrice":number|null,"discount":number|null,
                 "explicitFields":["productName|location|unit|originalPrice|discount"]}].
               Return [] when no item row is edited. quantityDelta is the spoken amount, never a generated total.
               Include a matching field in explicitFields only when the user actually supplied it.
            10. For SELL and ADD cards, a bare product plus positive quantity such as "Atlas 50" is an implicit
                item addition even when the user does not say "add" or "add to the list". Treat it as an edit to
                CURRENT_CARD, never as a standalone command or whole-list replacement:
                - If exactly one compatible existing row can be identified by product and any explicitly supplied
                  location, unit, price, and discount, use INCREMENT. quantityDelta is the spoken quantity, and the
                  returned row quantity is the original quantity plus quantityDelta. Example: an existing Atlas 100
                  plus "Atlas 50" becomes one Atlas 150 row.
                - When the user does not state a price, do not compare against the catalog price. INCREMENT the one
                  compatible existing row and preserve that row's current originalPrice. Example: Atlas 20 at 210
                  plus "add 20 more Atlas" becomes Atlas 40 at 210.
                - When the user explicitly states the same price as the compatible row, use INCREMENT. When the user
                  explicitly states a different price, use APPEND so the differently priced quantity stays in its own row.
                - If no compatible existing row exists, use APPEND. Preserve every original row and append one new
                  row with the spoken quantity. Example: an existing Atlas 100 plus "Silk 5" becomes Atlas 100 and
                  Silk 5.
                - Never guess between multiple compatible rows: leave CURRENT_CARD unchanged and return a warning.
                Equivalent English, Russian, Kyrgyz, and mixed speech follows the same rule.
            11. Explicit new-row wording such as "add row", "another row", "new row", "отдельной строкой",
                "новая строка", "башка сап", or "жаңы сап" means APPEND even when all attributes are identical.
                If the user explicitly supplies a different location, unit, price, or discount from an existing row,
                use APPEND. Preserve every original row and append exactly one genuinely new row.
                Never copy originalPrice, discount, finalPrice, or totalPrice from an existing or neighboring row.
                For an appended row, when the user did not explicitly state a price, set originalPrice to 0 or null
                and do not include originalPrice in explicitFields. When the user did not explicitly state a discount,
                set discount to 0 and do not include discount in explicitFields. Preserve an appended price or discount
                only when the user explicitly supplied it and the corresponding field is listed in explicitFields.
            12. Remove or replace rows only for explicit destructive wording. Use REMOVE or REPLACE metadata and keep
                the full intended items array. Never remove an original because a model-generated copy changed totals,
                finalPrice, calculationSteps, or other derived fields.
            13. Product names must be matched by the most specific exact phrase before fuzzy matching. If known products
               contain similar names where one is contained in another, such as "Atlas" and "Atlas Big", and the user
               says the longer name, use the longer exact known product. Never shorten "Atlas Big" to "Atlas", and
               never edit an existing "Atlas" item when the instruction is to add/append "Atlas Big".
            14. Use exact known product names for existing products when the edit clearly refers to one. Use fuzzy matching
               only for spelling/transcription corrections, not to collapse a longer distinct product name into a shorter
               existing product. For CREATE of a new product, keep the user's product name exactly.
            15. Use exact known location names when clearly matching a known location. If the user gives a new target location, keep it as spoken.
            16. If the instruction is a new standalone command rather than an edit to CURRENT_CARD, return CURRENT_CARD unchanged, changedFields [], itemEdits [], and warning "This looks like a new command, not an edit to the current card." A bare product-plus-quantity instruction covered by rule 10 is not a standalone command.
            17. If the instruction is ambiguous or could edit more than one thing, return the original card unchanged, set changedFields to [], itemEdits [], and add a warning explaining what is unclear.
            18. Never execute the command. Never return SQL. Never explain outside JSON.

            CommandDTO fields:
            operation, productName, quantity, unit, location, updateType, updatedValue, originalPrice,
            discount, fromLocation, toLocation, message, items, warnings.
            """;

    public JsonNode convertTextToJson(String userInput, List<String> knownProductNames, Long userId) throws Exception {
        return convertTextToJson(userInput, knownProductNames, userId, null);
    }

    public JsonNode convertTextToJson(String userInput, List<String> knownProductNames, Long userId,
            TranscriptionResult transcriptionResult) throws Exception {
        String systemPrompt = SYSTEM_PROMPT;

        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        String dayOfWeek = now.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH);
        String formattedDate = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        systemPrompt += "\n\nCURRENT CONTEXT:\n- Current date/time: " + formattedDate + " (" + dayOfWeek + ")\n- Use this to resolve relative time references like 'today', 'yesterday', 'this week', 'last month'.\n";

        if (knownProductNames != null && !knownProductNames.isEmpty()) {
            String productList = String.join(", ", knownProductNames);
            systemPrompt += "\nKNOWN PRODUCTS: [" + productList + "]";
        }

        if (transcriptionResult != null) {
            systemPrompt += "\n\nVOICE TRANSCRIPTION CONTEXT:\n";
            if (transcriptionResult.languageCode() != null && !transcriptionResult.languageCode().isBlank()) {
                systemPrompt += "- Detected language: " + transcriptionResult.languageCode() + "\n";
            }
            if (transcriptionResult.languageProbability() > 0) {
                systemPrompt += "- Language probability: " + transcriptionResult.languageProbability() + "\n";
            }
            if (transcriptionResult.lowConfidenceWords() != null && !transcriptionResult.lowConfidenceWords().isEmpty()) {
                systemPrompt += "- Low-confidence transcript words: [" + String.join(", ", transcriptionResult.lowConfidenceWords()) + "]\n";
            }
            systemPrompt += "- If the transcript looks phonetically wrong in Kyrgyz, Russian, or mixed speech, recover the intended inventory command using known products, operation words, quantities, and locations. Do not invent products unless the operation is CREATE.\n";
        }

        ObjectNode root = buildRequestBody(true);

        ArrayNode messages = mapper.createArrayNode();
        messages.add(createMessage("system", systemPrompt));
        messages.add(createMessage("user", userInput));
        setMessages(root, messages);

        String responseContent = callModel(root);
        JsonNode responseJson = mapper.readTree(responseContent);

        return responseJson;
    }

    public JsonNode convertTextToJson(String userInput) throws Exception {
        return convertTextToJson(userInput, null, null);
    }

    public JsonNode editCommandCard(String userInput, CommandDTO currentCard, List<String> knownProductNames,
            List<String> knownLocationNames, TranscriptionResult transcriptionResult) throws Exception {
        String systemPrompt = CARD_EDIT_SYSTEM_PROMPT;

        if (knownProductNames != null && !knownProductNames.isEmpty()) {
            systemPrompt += "\nKNOWN PRODUCTS: [" + String.join(", ", knownProductNames) + "]\n";
        }
        if (knownLocationNames != null && !knownLocationNames.isEmpty()) {
            systemPrompt += "\nKNOWN LOCATIONS: [" + String.join(", ", knownLocationNames) + "]\n";
        }
        if (transcriptionResult != null) {
            systemPrompt += "\nVOICE TRANSCRIPTION CONTEXT:\n";
            if (transcriptionResult.languageCode() != null && !transcriptionResult.languageCode().isBlank()) {
                systemPrompt += "- Detected language: " + transcriptionResult.languageCode() + "\n";
            }
            if (transcriptionResult.languageProbability() > 0) {
                systemPrompt += "- Language probability: " + transcriptionResult.languageProbability() + "\n";
            }
            if (transcriptionResult.lowConfidenceWords() != null && !transcriptionResult.lowConfidenceWords().isEmpty()) {
                systemPrompt += "- Low-confidence transcript words: ["
                        + String.join(", ", transcriptionResult.lowConfidenceWords()) + "]\n";
            }
            systemPrompt += "- Correct likely transcription mistakes only when the edit intent is clear from the current card and known inventory names.\n";
        }

        ObjectNode root = buildCardEditRequestBody();
        ArrayNode messages = mapper.createArrayNode();
        messages.add(createMessage("system", systemPrompt));

        ObjectNode userPayload = mapper.createObjectNode();
        userPayload.put("instruction", userInput == null ? "" : userInput);
        userPayload.set("currentCard", mapper.valueToTree(currentCard));
        messages.add(createMessage("user", mapper.writeValueAsString(userPayload)));
        setMessages(root, messages);

        String responseContent = callModel(root);
        return mapper.readTree(responseContent);
    }

    private ObjectNode buildRequestBody(boolean jsonMode) {
        ObjectNode root = buildBaseRequestBody();
        if (isDeepSeekProvider()) {
            if (jsonMode) {
                ObjectNode responseFormat = mapper.createObjectNode();
                responseFormat.put("type", "json_object");
                root.set("response_format", responseFormat);
            }
            return root;
        }

        if (jsonMode) {
            addOpenAiCommandSchema(root);
        } else {
            ObjectNode responseFormat = mapper.createObjectNode();
            responseFormat.put("type", "text");

            ObjectNode text = mapper.createObjectNode();
            text.set("format", responseFormat);
            root.set("text", text);
        }
        return root;
    }

    private ObjectNode buildBaseRequestBody() {
        ObjectNode root = mapper.createObjectNode();
        if (isDeepSeekProvider()) {
            root.put("model", deepSeekModel);
            root.put("stream", false);
            ObjectNode thinking = mapper.createObjectNode();
            thinking.put("type", deepSeekThinkingEnabled ? "enabled" : "disabled");
            root.set("thinking", thinking);
            return root;
        }

        root.put("model", openAiModel);
        root.put("store", false);
        if (openAiReasoningEffort != null && !openAiReasoningEffort.isBlank()) {
            ObjectNode reasoning = mapper.createObjectNode();
            reasoning.put("effort", openAiReasoningEffort);
            root.set("reasoning", reasoning);
        }
        return root;
    }

    private void addOpenAiCommandSchema(ObjectNode root) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();
        ObjectNode commands = mapper.createObjectNode();
        commands.put("type", "array");
        ObjectNode commandItem = mapper.createObjectNode();
        commandItem.put("type", "object");
        commandItem.put("additionalProperties", true);
        commands.set("items", commandItem);
        properties.set("commands", commands);

        ArrayNode required = mapper.createArrayNode();
        required.add("commands");

        schema.set("properties", properties);
        schema.set("required", required);
        schema.put("additionalProperties", true);

        ObjectNode responseFormat = mapper.createObjectNode();
        responseFormat.put("type", "json_schema");
        responseFormat.put("name", "inventory_commands");
        responseFormat.put("strict", false);
        responseFormat.set("schema", schema);

        ObjectNode text = mapper.createObjectNode();
        text.set("format", responseFormat);
        root.set("text", text);
    }

    private ObjectNode buildCardEditRequestBody() {
        ObjectNode root = buildBaseRequestBody();
        if (isDeepSeekProvider()) {
            ObjectNode responseFormat = mapper.createObjectNode();
            responseFormat.put("type", "json_object");
            root.set("response_format", responseFormat);
            return root;
        }

        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();
        ObjectNode card = mapper.createObjectNode();
        card.put("type", "object");
        card.put("additionalProperties", true);
        properties.set("card", card);

        ObjectNode changedFields = mapper.createObjectNode();
        changedFields.put("type", "array");
        ObjectNode changedFieldItem = mapper.createObjectNode();
        changedFieldItem.put("type", "string");
        changedFields.set("items", changedFieldItem);
        properties.set("changedFields", changedFields);

        ObjectNode warnings = mapper.createObjectNode();
        warnings.put("type", "array");
        ObjectNode warningItem = mapper.createObjectNode();
        warningItem.put("type", "string");
        warnings.set("items", warningItem);
        properties.set("warnings", warnings);

        ObjectNode itemEdits = mapper.createObjectNode();
        itemEdits.put("type", "array");
        ObjectNode itemEdit = mapper.createObjectNode();
        itemEdit.put("type", "object");
        itemEdit.put("additionalProperties", true);
        itemEdits.set("items", itemEdit);
        properties.set("itemEdits", itemEdits);

        ArrayNode required = mapper.createArrayNode();
        required.add("card");
        required.add("changedFields");
        required.add("warnings");
        required.add("itemEdits");
        schema.set("properties", properties);
        schema.set("required", required);
        schema.put("additionalProperties", false);

        ObjectNode responseFormat = mapper.createObjectNode();
        responseFormat.put("type", "json_schema");
        responseFormat.put("name", "command_card_edit");
        responseFormat.put("strict", false);
        responseFormat.set("schema", schema);

        ObjectNode text = mapper.createObjectNode();
        text.set("format", responseFormat);
        root.set("text", text);
        return root;
    }

    private ObjectNode createMessage(String role, String content) {
        return mapper.createObjectNode().put("role", role).put("content", content);
    }

    private void setMessages(ObjectNode requestBody, ArrayNode messages) {
        requestBody.set(isDeepSeekProvider() ? "messages" : "input", messages);
    }

    private String callModel(ObjectNode requestBody) {
        String apiKey = activeApiKey();
        String providerName = providerName();
        if (apiKey == null || apiKey.isBlank()) {
            throw new com.is.aigul.exception.ProviderUnavailableException();
        }

        try {
            String body = mapper.writeValueAsString(requestBody);

            Request request = new Request.Builder()
                    .url(activeUrl())
                    .header("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(body, MediaType.parse("application/json; charset=utf-8")))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                ResponseBody bodyResponse = response.body();
                String responseBody = ProviderResponseReader.text(bodyResponse, 2 * 1024 * 1024);

                if (!response.isSuccessful()) {
                    log.error("{} API call failed with status {}", providerName, response.code());
                    throw new RuntimeException(providerName + " API call failed: " + response.code());
                }

                String output = isDeepSeekProvider()
                        ? extractChatCompletionContent(responseBody)
                        : extractResponsesOutputText(responseBody);
                if (output == null || output.isBlank()) {
                    log.error("{} returned empty output text", providerName);
                    throw new RuntimeException(providerName + " returned empty message");
                }

                return output.trim();
            }

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Error calling " + providerName + ": " + e.getMessage(), e);
        }
    }

    String extractChatCompletionContent(String responseBody) throws Exception {
        JsonNode json = mapper.readTree(responseBody);
        return json.path("choices").path(0).path("message").path("content").asText("");
    }

    String extractResponsesOutputText(String responseBody) throws Exception {
        JsonNode json = mapper.readTree(responseBody);
        JsonNode outputText = json.get("output_text");
        if (outputText != null && outputText.isTextual()) {
            return outputText.asText();
        }

        JsonNode output = json.get("output");
        if (output == null || !output.isArray()) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        for (JsonNode item : output) {
            JsonNode content = item.get("content");
            if (content == null || !content.isArray()) {
                continue;
            }
            for (JsonNode contentItem : content) {
                if ("output_text".equals(contentItem.path("type").asText()) && contentItem.get("text") != null) {
                    result.append(contentItem.get("text").asText());
                }
            }
        }

        return result.toString();
    }

    private boolean isDeepSeekProvider() {
        if (provider == null || provider.isBlank() || "deepseek".equalsIgnoreCase(provider)) {
            return true;
        }
        if ("openai".equalsIgnoreCase(provider)) {
            return false;
        }
        throw new IllegalStateException("Unsupported AI provider: " + provider);
    }

    private String activeApiKey() {
        return isDeepSeekProvider() ? deepSeekApiKey : openAiApiKey;
    }

    private String activeUrl() {
        return isDeepSeekProvider() ? deepSeekUrl : openAiUrl;
    }

    private String providerName() {
        return isDeepSeekProvider() ? "DeepSeek" : "OpenAI";
    }

    private String safe(String value) {
        if (value == null)
            return "";
        return value.replace("'", "''").trim();
    }
}
