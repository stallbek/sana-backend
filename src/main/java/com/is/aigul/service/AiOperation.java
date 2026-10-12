package com.is.aigul.service;

public enum AiOperation {
    TEXT_COMMAND(1, true, false),
    TEXT_CARD_EDIT(1, true, false),
    UPLOADED_TRANSCRIPTION(1, true, false),
    FULL_AUDIO_COMMAND(2, true, false),
    AUDIO_CARD_EDIT(2, true, false),
    REALTIME_TOKEN(2, true, true),
    GET_EXECUTION(2, false, false);

    private final int units;
    private final boolean primarySubmission;
    private final boolean realtimeToken;

    AiOperation(int units, boolean primarySubmission, boolean realtimeToken) {
        this.units = units;
        this.primarySubmission = primarySubmission;
        this.realtimeToken = realtimeToken;
    }

    public int units() { return units; }
    public boolean isPrimarySubmission() { return primarySubmission; }
    public boolean isRealtimeToken() { return realtimeToken; }
}
