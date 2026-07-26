package com.smartinterview.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@TableName("interview_wrong_bookmark")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewWrongBookmark {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long questionReportId;
    private LocalDateTime createTime;
}
