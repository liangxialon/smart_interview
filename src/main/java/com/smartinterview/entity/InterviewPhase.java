package com.smartinterview.entity;

import lombok.Getter;

/**
 * 面试阶段 FSM 状态枚举
 * 根据已答题数自动推进：WARM_UP(1-3) → PROJECT_DEEP_DIVE(4-8) → SYSTEM_DEEP(9+)
 */
@Getter
public enum InterviewPhase {

    WARM_UP(1, 3,
            "当前处于【暖场破冰】阶段，重点考察基础八股文。方向：是什么、怎么用、基本原理。示例：HashMap的扩容机制？"),

    PROJECT_DEEP_DIVE(4, 8,
            "当前处于【项目深挖】阶段，必须结合简历画像中的项目细节提问。方向：为什么这么设计、遇到什么坑、怎么解决。示例：你项目中用了Redis缓存，缓存穿透怎么处理的？"),

    SYSTEM_DEEP(9, Integer.MAX_VALUE,
            "当前处于【系统深潜】阶段，考察底层原理+系统设计。方向：源码级深度、架构Trade-off、高可用推演。示例：基于AQS手写分布式锁怎么应对脑裂？");

    /** 该阶段起始题号（从1开始） */
    private final int startQuestion;
    /** 该阶段结束题号 */
    private final int endQuestion;
    /** 极简阶段提示词（注入系统提示词） */
    private final String phasePrompt;

    InterviewPhase(int startQuestion, int endQuestion, String phasePrompt) {
        this.startQuestion = startQuestion;
        this.endQuestion = endQuestion;
        this.phasePrompt = phasePrompt;
    }

    /**
     * 根据已答题数（从1开始）判断当前阶段
     */
    public static InterviewPhase of(int questionCount) {
        if (questionCount <= 0) return WARM_UP;
        for (InterviewPhase phase : values()) {
            if (questionCount >= phase.startQuestion && questionCount <= phase.endQuestion) {
                return phase;
            }
        }
        return SYSTEM_DEEP;
    }
}
