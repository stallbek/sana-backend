package com.is.aigul.controller;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.is.aigul.dto.CommandDTO;
import com.is.aigul.model.LocationNames;
import com.is.aigul.repository.LocationNamesRepository;
import com.is.aigul.repository.ProductRepository;
import com.is.aigul.exception.AiRateLimitException;
import com.is.aigul.service.AiOperation;
import com.is.aigul.service.AiUsageLimitService;
import com.is.aigul.service.AuthService;
import com.is.aigul.service.CardEditService;
import com.is.aigul.service.FullService;
import com.is.aigul.service.RealtimeTokenService;
import com.is.aigul.service.WhisperService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.List;


@RestController
@RequestMapping("/api/ai/audio/")
public class AudioController {

    @Autowired
    private WhisperService whisperService;
    @Autowired
    private FullService fullService;
    @Autowired
    private RealtimeTokenService realtimeTokenService;
    @Autowired
    private CardEditService cardEditService;
    @Autowired
    private AuthService authService;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private LocationNamesRepository locationNamesRepository;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AiUsageLimitService aiUsageLimitService;

    @PostMapping("/transcribe")
    public String transcribe(@RequestParam("file") MultipartFile file, @RequestHeader("token") String token) throws IOException {
        Long userId = authService.getUserId(token);
        if (userId == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized");
        aiUsageLimitService.validateUpload(file);
        // Save file temporarily
        File tempFile = null;
        try (AiUsageLimitService.Permit ignored = aiUsageLimitService.reserve(userId, AiOperation.UPLOADED_TRANSCRIPTION)) {
            tempFile = File.createTempFile("audio", ".m4a");
            file.transferTo(tempFile);

            List<String> knownProducts = productRepository.findAllNamesByUserId(userId);
            List<String> knownLocations = locationNamesRepository.findByUserId(userId)
                    .stream()
                    .map(LocationNames::getLocationName)
                    .toList();

            return whisperService.audioToText(tempFile, knownProducts, knownLocations);
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    @PostMapping(value = "/full", consumes = "multipart/form-data")
    public ResponseEntity<java.util.List<CommandDTO>> processFullAudio(@RequestParam("file") MultipartFile multipartFile, @RequestHeader("token") String token) {
        Long userId = authService.getUserId(token);
        if (userId == null) return ResponseEntity.status(401).build();
        aiUsageLimitService.validateUpload(multipartFile);
        File tempFile = null;
        try (AiUsageLimitService.Permit ignored = aiUsageLimitService.reserve(userId, AiOperation.FULL_AUDIO_COMMAND)) {
            // Save to a temporary file (Spring MultipartFile -> File)
            String originalName = multipartFile.getOriginalFilename();
            String ext = ".bin";

            if (originalName != null && originalName.contains(".")) {
                ext = originalName.substring(originalName.lastIndexOf("."));
            }

            tempFile = File.createTempFile("aigul_audio_", ext);
            multipartFile.transferTo(tempFile);

            // Process full AI pipeline
            java.util.List<CommandDTO> result = fullService.processAudioFully(tempFile, userId);

            // Return the validated command JSON
            return ResponseEntity.ok(result);

        } catch (AiRateLimitException e) {
            throw e;
        } catch (IOException e) {
            throw new RuntimeException("Error saving temporary audio file: " + e.getMessage(), e);

        } catch (com.is.aigul.exception.ProviderUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Error processing full audio command: " + e.getMessage(), e);

        } finally {
            // Clean up temporary file
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    @PostMapping("/realtime-token")
    public ResponseEntity<?> createRealtimeToken(@RequestHeader("token") String token) {
        Long userId = authService.getUserId(token);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }

        try (AiUsageLimitService.Permit ignored = aiUsageLimitService.reserve(userId, AiOperation.REALTIME_TOKEN)) {
            List<String> knownProducts = productRepository.findAllNamesByUserId(userId);
            List<String> knownLocations = locationNamesRepository.findByUserId(userId)
                    .stream()
                    .map(LocationNames::getLocationName)
                    .toList();

            return ResponseEntity.ok(realtimeTokenService.createToken(knownProducts, knownLocations));
        } catch (AiRateLimitException e) {
            throw e;
        } catch (IOException e) {
            return ResponseEntity.status(502)
                    .body(java.util.Map.of("error", "Unable to create realtime transcription token"));
        }
    }

    @PostMapping(value = "/edit", consumes = "multipart/form-data")
    public ResponseEntity<CommandDTO> editAudioCard(@RequestParam("file") MultipartFile multipartFile,
            @RequestParam("card") String cardJson, @RequestHeader("token") String token) {
        Long userId = authService.getUserId(token);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }

        try {
            aiUsageLimitService.validateUpload(multipartFile);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        CommandDTO card;
        try {
            card = objectMapper.readValue(cardJson, CommandDTO.class);
            if (card.getOperation() == null || card.getOperation().isBlank()) {
                return ResponseEntity.badRequest().build();
            }
        } catch (JacksonException e) {
            return ResponseEntity.badRequest().build();
        }

        File tempFile = null;
        try (AiUsageLimitService.Permit ignored = aiUsageLimitService.reserve(userId, AiOperation.AUDIO_CARD_EDIT)) {
            String originalName = multipartFile.getOriginalFilename();
            String ext = ".bin";
            if (originalName != null && originalName.contains(".")) {
                ext = originalName.substring(originalName.lastIndexOf("."));
            }

            tempFile = File.createTempFile("aigul_card_edit_audio_", ext);
            multipartFile.transferTo(tempFile);

            return ResponseEntity.ok(cardEditService.editAudioCard(tempFile, card, userId));
        } catch (AiRateLimitException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (IOException e) {
            throw new RuntimeException("Error saving temporary audio file: " + e.getMessage(), e);
        } catch (com.is.aigul.exception.ProviderUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Error processing audio card edit: " + e.getMessage(), e);
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }
}
