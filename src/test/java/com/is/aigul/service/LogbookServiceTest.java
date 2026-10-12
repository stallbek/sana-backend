package com.is.aigul.service;

import com.is.aigul.model.AppUser;
import com.is.aigul.model.Logbook;
import com.is.aigul.repository.LogbookRepository;
import com.is.aigul.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LogbookServiceTest {

    @Test
    void actorFallbackUsesNeutralLabelWithoutNameOrUsername() {
        LogbookRepository logbookRepository = mock(LogbookRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        AppUser user = new AppUser();
        user.setId(7L);
        user.setEmail("private@example.com");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        LogbookService service = new LogbookService(logbookRepository, userRepository);
        service.logAction("ADD", "ignored", null, 1, null, "created item", null, 7L);

        verify(logbookRepository).save(argThat((Logbook log) ->
                "by User: created item".equals(log.getNotes())));
    }
}
