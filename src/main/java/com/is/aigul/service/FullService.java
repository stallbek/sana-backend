package com.is.aigul.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.is.aigul.dto.CommandDTO;
import com.is.aigul.model.LocationNames;
import com.is.aigul.repository.ProductRepository;
import com.is.aigul.repository.LocationNamesRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FullService {

    private static final Logger log = LoggerFactory.getLogger(FullService.class);

    private final WhisperService audioTranscriptionService;
    private final GptService aiParserService;
    private final CommandPreprocessorService commandProcessorService;
    private final ProductRepository productRepository;
    private final LocationNamesRepository locationNamesRepository;
    private final ShortTermMemoryService shortTermMemoryService;
    @Autowired
    private ObjectMapper objectMapper;

    public List<CommandDTO> processAudioFully(File file, Long userId) {
        try {
            long startedAt = System.nanoTime();

            List<String> knownProducts = productRepository.findAllNamesByUserId(userId);
            List<String> knownLocations = locationNamesRepository.findByUserId(userId)
                    .stream()
                    .map(LocationNames::getLocationName)
                    .toList();

            TranscriptionResult transcription = audioTranscriptionService.transcribe(file, knownProducts, knownLocations);
            String transcribedText = transcription.text();
            if (transcribedText == null || transcribedText.isBlank()) {
                throw new RuntimeException("Transcription failed: empty text result.");
            }

            long parseStartedAt = System.nanoTime();
            JsonNode parsedJson = aiParserService.convertTextToJson(transcribedText, knownProducts, userId, transcription);
            long parseElapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - parseStartedAt);
            log.info("AI audio command parse completed in {} ms", parseElapsedMs);
            if (parsedJson == null || parsedJson.isEmpty()) {
                throw new RuntimeException("AI parsing failed: no command returned.");
            }

            JsonNode commandsArray = parsedJson.get("commands");
            if (commandsArray == null || !commandsArray.isArray()) {
                throw new RuntimeException("Invalid response from GPT: 'commands' array missing.");
            }

            long preprocessStartedAt = System.nanoTime();
            List<CommandDTO> correctedCommands = preprocessCommands(commandsArray, userId);
            long preprocessElapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - preprocessStartedAt);
            log.info("Audio command preprocessing completed in {} ms", preprocessElapsedMs);

            shortTermMemoryService.rememberPendingCommands(userId, transcribedText, correctedCommands);
            long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            log.info("Full audio command processing completed in {} ms", elapsedMs);
            return correctedCommands;

        } catch (com.is.aigul.exception.ProviderUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Error during full audio command processing: " + e.getMessage(), e);
        }
    }


    public List<CommandDTO> processTextFully(String text, Long userId) {
        try {
            long startedAt = System.nanoTime();
            if (text == null) {
                throw new RuntimeException("Process failed: empty text result.");
            }

            List<String> knownProducts = productRepository.findAllNamesByUserId(userId);

            long parseStartedAt = System.nanoTime();
            JsonNode parsedJson = aiParserService.convertTextToJson(text, knownProducts, userId);
            long parseElapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - parseStartedAt);
            log.info("AI text command parse completed in {} ms", parseElapsedMs);
            if (parsedJson == null || parsedJson.isEmpty()) {
                throw new RuntimeException("AI parsing failed: no command returned.");
            }

            JsonNode commandsArray = parsedJson.get("commands");
            if (commandsArray == null || !commandsArray.isArray()) {
                throw new RuntimeException("Invalid response from GPT: 'commands' array missing.");
            }

            long preprocessStartedAt = System.nanoTime();
            List<CommandDTO> correctedCommands = preprocessCommands(commandsArray, userId);

            long preprocessElapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - preprocessStartedAt);
            shortTermMemoryService.rememberPendingCommands(userId, text, correctedCommands);
            long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            log.info("Text command preprocessing completed in {} ms", preprocessElapsedMs);
            log.info("Full text command processing completed in {} ms", elapsedMs);
            return correctedCommands;
        } catch (com.is.aigul.exception.ProviderUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Error during text command processing: " + e.getMessage(), e);
        }
    }

    private List<CommandDTO> preprocessCommands(JsonNode commandsArray, Long userId) throws Exception {
        List<CommandDTO> correctedCommands = new java.util.ArrayList<>();
        for (JsonNode cmdNode : commandsArray) {
            CommandDTO parsedCommand = objectMapper.treeToValue(cmdNode, CommandDTO.class);
            if (parsedCommand == null) {
                continue;
            }
            CommandDTO correctedCommand = commandProcessorService.preprocess(parsedCommand, userId);
            if (correctedCommand != null) {
                correctedCommands.add(correctedCommand);
            }
        }
        return correctedCommands;
    }
}
