package com.is.aigul.dto;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class ActionResponse {
    private String type;
    private String message;
    private Object data;
    private List<String> insights;

    public ActionResponse(String type, String message, Object data) {
        this.type = type;
        this.message = message;
        this.data = data;
        this.insights = new ArrayList<>();
    }

    public void addInsight(String insight) {
        if (this.insights == null) this.insights = new ArrayList<>();
        this.insights.add(insight);
    }
}
