package com.smartinterview.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import java.util.List;

@Data
public class InterviewReportVO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sessionId;
    private Integer totalScore;    // AVG(score) 取整
    private Integer questionCount; // 总题数
    private Integer correctCount;  // 正确题数
    private String correctRate;    // 正确率（如 "62.5%"）
    private Boolean scoringComplete; // 是否所有题目评分完成
    private List<QuestionReportItem> items;

    @Data
    public static class QuestionReportItem {
        @JsonSerialize(using = ToStringSerializer.class)
        private Long questionReportId;
        private String questionText;
        private String userAnswer;
        private Integer score;
        private String comment;
        private Boolean isCorrect;
        private Boolean bookmarked;
    }
}
