package com.smartinterview.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WrongBookmarkVO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long bookmarkId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long questionReportId;
    private String questionText;
    private String userAnswer;
    private Integer score;
    private String comment;
    private Boolean isCorrect;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sessionId;
    private String sessionTitle;
    private LocalDateTime bookmarkTime;
}
