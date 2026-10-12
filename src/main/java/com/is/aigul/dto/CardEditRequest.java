package com.is.aigul.dto;

import lombok.Data;

@Data
public class CardEditRequest {
    private String text;
    private CommandDTO card;
}
