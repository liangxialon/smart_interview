package com.smartinterview.entity;

import lombok.Getter;

/**
 * 面试阶段 FSM 状态枚举
 * 根据已答题数 + 难度动态推进阶段
 *
 * 初级：暖场12题 → 项目深挖6题 → 底层0题
 * 中级：暖场5题  → 项目深挖8题 → 底层3题
 * 高级：暖场0题  → 项目深挖10题 → 底层5题
 */
@Getter
public enum InterviewPhase {

    WARM_UP("当前处于【暖场破冰】阶段，重点考察基础八股文。方向：是什么、怎么用、基本原理。"),

    PROJECT_DEEP_DIVE("当前处于【项目深挖】阶段，必须结合简历画像中的项目细节提问。方向：为什么这么设计、遇到什么坑、怎么解决。"),

    SYSTEM_DEEP("当前处于【系统深潜】阶段，考察底层原理+系统设计。方向：源码级深度、架构Trade-off、高可用推演。");

    /** 极简阶段提示词（注入系统提示词） */
    private final String phasePrompt;

    InterviewPhase(String phasePrompt) {
        this.phasePrompt = phasePrompt;
    }

    /**
     * 根据已答题数 + 难度动态判断当前阶段
     *
     * @param questionCount 当前是第几题（从1开始）
     * @param difficulty    "初级"/"中级"/"高级"，默认按中级处理
     */
    public static InterviewPhase of(int questionCount, String difficulty) {
        if (questionCount <= 0) return WARM_UP;

        int warmUpEnd;   // 暖场结束题号
        int projectEnd;  // 项目深挖结束题号
        switch (difficulty != null ? difficulty : "") {
            case "初级":
                warmUpEnd = 12;   // 暖场 1-12题
                projectEnd = 18;  // 项目 13-18题
                break;
            case "高级":
                warmUpEnd = 0;    // 无暖场
                projectEnd = 10;  // 项目 1-10题
                break;
            default:              // "中级" 或其他
                warmUpEnd = 5;    // 暖场 1-5题
                projectEnd = 13;  // 项目 6-13题
                break;
        }

        if (questionCount <= warmUpEnd) return WARM_UP;
        if (questionCount <= projectEnd) return PROJECT_DEEP_DIVE;
        return SYSTEM_DEEP;
    }

    /**
     * 获取该难度下的最大题目数（达到后自动结束面试）
     * 初级18题 / 中级16题 / 高级15题  去除第一条用户发送的请开始面试消息
     */
    public static int getMaxQuestions(String difficulty) {
        switch (difficulty != null ? difficulty : "") {
            case "初级": return 19;
            case "高级": return 15;
            default:     return 17;
        }
    }
}
