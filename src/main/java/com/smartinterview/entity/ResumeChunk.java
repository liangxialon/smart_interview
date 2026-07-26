package com.smartinterview.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 简历切片实体（对应 ES 索引 sys_resume_chunk_index）
 * 不走 MySQL，仅用于 ES 存储和检索
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResumeChunk {
    /** ES 文档 ID = resumeId_chunkIndex */
    private String id;
    /** 关联的简历 ID */
    private Long resumeId;
    /** 用户 ID（用于按用户隔离检索） */
    private Long userId;
    /** 切片文本内容 */
    private String content;
    /** 切片序号 */
    private Integer chunkIndex;
}
