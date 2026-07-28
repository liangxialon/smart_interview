package com.smartinterview.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class QuestionScoreMessage {
    private Long reportId;
    private Long sessionId;
    private Long messageId;
    private String aiQuestion;
    private String userAnswer;
    private String standardAnswer;
}
