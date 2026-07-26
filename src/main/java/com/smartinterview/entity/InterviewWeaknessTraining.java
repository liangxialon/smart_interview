package com.smartinterview.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@TableName("interview_weakness_training")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewWeaknessTraining {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private String weaknessType;
    private String trainingData;
    private LocalDateTime createTime;
}
