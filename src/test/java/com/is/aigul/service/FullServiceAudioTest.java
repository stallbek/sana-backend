package com.is.aigul.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.is.aigul.dto.CommandDTO;
import com.is.aigul.model.LocationNames;
import com.is.aigul.repository.LocationNamesRepository;
import com.is.aigul.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FullServiceAudioTest {

    @Test
    void processAudioFullyUsesOneSttCallAndOneGptParseCall() throws Exception {
        Long userId = 7L;
        WhisperService whisperService = mock(WhisperService.class);
        GptService gptService = mock(GptService.class);
        CommandPreprocessorService preprocessor = mock(CommandPreprocessorService.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        LocationNamesRepository locationNamesRepository = mock(LocationNamesRepository.class);
        ShortTermMemoryService memoryService = mock(ShortTermMemoryService.class);
        ObjectMapper mapper = new ObjectMapper();

        FullService fullService = new FullService(
                whisperService,
                gptService,
                preprocessor,
                productRepository,
                locationNamesRepository,
                memoryService
        );
        ReflectionTestUtils.setField(fullService, "objectMapper", mapper);

        LocationNames location = new LocationNames();
        location.setLocationName("Osh");
        when(productRepository.findAllNamesByUserId(userId)).thenReturn(List.of("Atlas"));
        when(locationNamesRepository.findByUserId(userId)).thenReturn(List.of(location));

        TranscriptionResult transcription = new TranscriptionResult("сат 5 атлас", "ky", 0.8, List.of("атлас"));
        when(whisperService.transcribe(any(File.class), eq(List.of("Atlas")), eq(List.of("Osh"))))
                .thenReturn(transcription);

        ObjectNode command = mapper.createObjectNode();
        command.put("operation", "SELL");
        ObjectNode root = mapper.createObjectNode();
        root.set("commands", mapper.createArrayNode().add(command));
        when(gptService.convertTextToJson(eq("сат 5 атлас"), eq(List.of("Atlas")), eq(userId), eq(transcription)))
                .thenReturn(root);

        CommandDTO processed = new CommandDTO();
        processed.setOperation("SELL");
        when(preprocessor.preprocess(any(CommandDTO.class), eq(userId))).thenReturn(processed);

        File audio = Files.createTempFile("aigul-full", ".m4a").toFile();
        try {
            List<CommandDTO> result = fullService.processAudioFully(audio, userId);

            assertThat(result).containsExactly(processed);
            verify(whisperService, times(1)).transcribe(any(File.class), eq(List.of("Atlas")), eq(List.of("Osh")));
            verify(gptService, times(1)).convertTextToJson("сат 5 атлас", List.of("Atlas"), userId, transcription);
            verify(memoryService, times(1)).rememberPendingCommands(eq(userId), anyString(), eq(List.of(processed)));
            verifyNoMoreInteractions(memoryService);
        } finally {
            Files.deleteIfExists(audio.toPath());
        }
    }
}
