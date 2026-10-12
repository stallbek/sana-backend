package com.is.aigul.service;

import com.is.aigul.dto.CommandDTO;
import com.is.aigul.dto.SellItemDTO;
import com.is.aigul.exception.UnsupportedCommandException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CommandValidatorTest {
    @Test void rejectsSqlQueryCommandsRegardlessOfCase() {
        CommandDTO cmd = command(" gEt ");
        assertThatThrownBy(() -> CommandValidator.validateMutation(cmd)).isInstanceOf(UnsupportedCommandException.class);
    }
    @Test void rejectsNullNegativeAndOversizedModelOutput() {
        assertThatThrownBy(() -> CommandValidator.validateDraft(null)).isInstanceOf(IllegalArgumentException.class);
        CommandDTO cmd = command("CREATE"); cmd.setQuantity(-1);
        assertThatThrownBy(() -> CommandValidator.validateDraft(cmd)).isInstanceOf(IllegalArgumentException.class);
        cmd.setQuantity(0); cmd.setProductName("a".repeat(256));
        assertThatThrownBy(() -> CommandValidator.validateDraft(cmd)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsNonFiniteAndNegativeMoney() {
        for (double amount : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1}) {
            CommandDTO cmd = command("CREATE"); cmd.setOriginalPrice(amount);
            assertThatThrownBy(() -> CommandValidator.validateMutation(cmd)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void rejectsMalformedNumericUpdates() {
        for (String value : List.of("NaN", "Infinity", "-1", "999999999999999999999")) {
            CommandDTO cmd = command("UPDATE"); cmd.setUpdateType("quantity"); cmd.setUpdatedValue(value); cmd.setLocation("Shop");
            assertThatThrownBy(() -> CommandValidator.validateMutation(cmd)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void rejectsMissingNullOrZeroQuantityItems() {
        CommandDTO cmd = command("SELL");
        assertThatThrownBy(() -> CommandValidator.validateMutation(cmd)).isInstanceOf(IllegalArgumentException.class);
        cmd.setItems(java.util.Arrays.asList((SellItemDTO)null));
        assertThatThrownBy(() -> CommandValidator.validateMutation(cmd)).isInstanceOf(IllegalArgumentException.class);
        SellItemDTO item = new SellItemDTO(); item.setProductName("Atlas"); item.setLocation("Shop"); cmd.setItems(List.of(item));
        assertThatThrownBy(() -> CommandValidator.validateMutation(cmd)).isInstanceOf(IllegalArgumentException.class);
        item.setQuantity(1); assertThatCode(() -> CommandValidator.validateMutation(cmd)).doesNotThrowAnyException();
    }
    private CommandDTO command(String operation) {
        CommandDTO cmd = new CommandDTO(); cmd.setOperation(operation); cmd.setProductName("Atlas"); return cmd;
    }
}
