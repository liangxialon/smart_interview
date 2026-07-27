package com.smartinterview.common.constants;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY="login:code:";
    public static final String CODE_RATE_KEY="code:rate:key";
    public static final Long LOGIN_CODE_TTL=360L;
    public static final String CLAIM_USER_ID="userId";
    public static final String LOGIN_USER="login:user:";
    public static final Long LOGIN_TOKEN_TTL=360L;
    public static final String USER_NICK_NAME="user_";
    public static final String INTERVIEW_CHAT_HISTORY="interview:chat:history:";
    public static final String INTERVIEW_LONG_TERM_MEMORY="interview:memory:";
    public static final String INTERVIEW_COMPRESS_LOCK="interview:compress:lock:";
    public static final String INTERVIEW_COMPRESS_BACKUP="interview:compress:backup:";
    /** FSM 面试阶段：interview:phase:{sessionId} → InterviewPhase 枚举名 */
    public static final String INTERVIEW_PHASE = "interview:phase:";
    /** FSM 已答题计数：interview:qcount:{sessionId} → int */
    public static final String INTERVIEW_QUESTION_COUNT = "interview:qcount:";
    public static final Long INTERVIEW_CHAT_TTL=360L;
    /** 简历AI分析防重锁：resume:analyze:lock:{resumeId} */
    public static final String RESUME_ANALYZE_LOCK = "resume:analyze:lock:";
    public static final Long RESUME_ANALYZE_LOCK_TTL = 5L;

}
