package com.is.aigul.controller;

import tools.jackson.databind.ObjectMapper;
import com.is.aigul.dto.CardEditRequest;
import com.is.aigul.dto.CommandDTO;
import com.is.aigul.exception.AiRateLimitException;
import com.is.aigul.repository.LocationNamesRepository;
import com.is.aigul.repository.ProductRepository;
import com.is.aigul.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiLimitControllerTest {

    @Test
    void textAndTextEditRoutesRejectBeforeProvidersOrCardMutation() throws Exception {
        AuthService auth = authenticated();
        AiUsageLimitService limits = rejectingLimits();
        FullService fullService = mock(FullService.class);
        CardEditService cardEditService = mock(CardEditService.class);
        CommandController commandController = new CommandController(
                mock(CommandPreprocessorService.class), fullService, cardEditService, auth,
                mock(ShortTermMemoryService.class), limits);
        CommandDTO card = command("ADD");
        CardEditRequest editRequest = new CardEditRequest();
        editRequest.setText("make it ten");
        editRequest.setCard(card);

        assertThatThrownBy(() -> commandController.processFullText("add atlas", "token"))
                .isInstanceOf(AiRateLimitException.class);
        assertThatThrownBy(() -> commandController.editTextCard(editRequest, "token"))
                .isInstanceOf(AiRateLimitException.class);

        verify(limits).reserve(7L, AiOperation.TEXT_COMMAND);
        verify(limits).reserve(7L, AiOperation.TEXT_CARD_EDIT);
        verifyNoInteractions(fullService, cardEditService);

        GptService gptService = mock(GptService.class);
        AiController aiController = new AiController(gptService, auth, mock(ProductRepository.class),
                mock(CommandPreprocessorService.class), limits);
        assertThatThrownBy(() -> aiController.parseCommand("sell atlas", "token"))
                .isInstanceOf(AiRateLimitException.class);
        verify(limits, times(2)).reserve(7L, AiOperation.TEXT_COMMAND);
        verifyNoInteractions(gptService);
    }

    @Test
    void everyAudioRouteRejectsBeforeItsPaidService() {
        AuthService auth = authenticated();
        AiUsageLimitService limits = rejectingLimits();
        WhisperService whisper = mock(WhisperService.class);
        FullService full = mock(FullService.class);
        RealtimeTokenService realtime = mock(RealtimeTokenService.class);
        CardEditService cardEdit = mock(CardEditService.class);
        AudioController controller = new AudioController();
        ReflectionTestUtils.setField(controller, "authService", auth);
        ReflectionTestUtils.setField(controller, "aiUsageLimitService", limits);
        ReflectionTestUtils.setField(controller, "whisperService", whisper);
        ReflectionTestUtils.setField(controller, "fullService", full);
        ReflectionTestUtils.setField(controller, "realtimeTokenService", realtime);
        ReflectionTestUtils.setField(controller, "cardEditService", cardEdit);
        ReflectionTestUtils.setField(controller, "productRepository", mock(ProductRepository.class));
        ReflectionTestUtils.setField(controller, "locationNamesRepository", mock(LocationNamesRepository.class));
        ReflectionTestUtils.setField(controller, "objectMapper", new ObjectMapper());
        MockMultipartFile audio = new MockMultipartFile("file", "voice.m4a", "audio/mp4", new byte[]{1});

        assertThatThrownBy(() -> controller.transcribe(audio, "token")).isInstanceOf(AiRateLimitException.class);
        assertThatThrownBy(() -> controller.processFullAudio(audio, "token")).isInstanceOf(AiRateLimitException.class);
        assertThatThrownBy(() -> controller.createRealtimeToken("token")).isInstanceOf(AiRateLimitException.class);
        assertThatThrownBy(() -> controller.editAudioCard(audio, "{\"operation\":\"ADD\"}", "token"))
                .isInstanceOf(AiRateLimitException.class);

        verify(limits).reserve(7L, AiOperation.UPLOADED_TRANSCRIPTION);
        verify(limits).reserve(7L, AiOperation.FULL_AUDIO_COMMAND);
        verify(limits).reserve(7L, AiOperation.REALTIME_TOKEN);
        verify(limits).reserve(7L, AiOperation.AUDIO_CARD_EDIT);
        verifyNoInteractions(whisper, full, realtime, cardEdit);
    }

    @Test
    void getExecutionIsRejectedBeforeProvidersOrDatabase() {
        AuthService auth = authenticated();
        AiUsageLimitService limits = rejectingLimits();
        InventoryService inventory = mock(InventoryService.class);
        InventoryController controller = new InventoryController();
        ReflectionTestUtils.setField(controller, "authService", auth);
        ReflectionTestUtils.setField(controller, "aiUsageLimitService", limits);
        ReflectionTestUtils.setField(controller, "inventoryService", inventory);
        ReflectionTestUtils.setField(controller, "shortTermMemoryService", mock(ShortTermMemoryService.class));

        assertThatThrownBy(() -> controller.executeCommand(command("GET"), "token"))
                .isInstanceOf(com.is.aigul.exception.UnsupportedCommandException.class);
        verifyNoInteractions(inventory, limits);
    }

    private AuthService authenticated() {
        AuthService auth = mock(AuthService.class);
        when(auth.getUserId("token")).thenReturn(7L);
        return auth;
    }

    private AiUsageLimitService rejectingLimits() {
        AiUsageLimitService limits = mock(AiUsageLimitService.class);
        when(limits.reserve(anyLong(), any())).thenThrow(new AiRateLimitException(12,
                AiRateLimitException.LimitType.BURST));
        return limits;
    }

    private CommandDTO command(String operation) {
        CommandDTO command = new CommandDTO();
        command.setOperation(operation);
        return command;
    }
}
