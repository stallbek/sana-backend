package com.is.aigul.service;
import com.is.aigul.dto.ActionResponse;
import com.is.aigul.dto.CommandDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

//removed transactional for this
@Service
public class InventoryService {

    @Autowired private ActionService actionService;

    public ActionResponse executeCommand(CommandDTO cmd, Long userId) {
        CommandValidator.validateMutation(cmd);
        switch (cmd.getOperation().trim().toUpperCase(java.util.Locale.ROOT)) {
            case "CREATE":
                return actionService.create(cmd, userId);
            case "ADD":
                return actionService.add(cmd, userId);
            case "SELL":
                return actionService.sell(cmd, userId);
            case "UPDATE":
                return actionService.update(cmd, userId);
            case "DELETE":
                return actionService.delete(cmd, userId);
            case "MOVE":
                return actionService.move(cmd, userId);
            default:
                return new ActionResponse(
                        "ERROR",
                        "Unknown operation: " + cmd.getOperation(),
                        null
                );
        }
    }

    public ActionResponse undoCommand(CommandDTO cmd, Long userId) {
        if (cmd == null || cmd.getOperation() == null || cmd.getOperation().isBlank()) {
            return new ActionResponse(
                    "ERROR",
                    "Invalid undo command: operation missing",
                    null
            );
        }

        return actionService.undo(cmd, userId);
    }
}
