package com.yourcompany.surveyai.call.application.dto.request;

public enum InterviewConversationSignal {
    ANSWER,
    NO_INPUT,
    REPEAT_REQUEST,
    IDENTITY_REQUEST,
    // The caller asks why a question is asked or objects to it ("neden soruyorsunuz?").
    REASON_REQUEST,
    STOP_REQUEST
}
