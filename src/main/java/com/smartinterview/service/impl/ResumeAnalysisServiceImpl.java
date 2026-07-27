package com.smartinterview.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.smartinterview.common.constants.RabbitConstants;
import com.smartinterview.common.constants.RedisConstants;
import com.smartinterview.common.exception.ResumeAnalysisException;
import com.smartinterview.common.exception.ResumeNotFindException;
import com.smartinterview.common.exception.ResumeUploadException;
import com.smartinterview.common.manager.ResumeStateManager;
import com.smartinterview.common.util.AliOssUtil;
import com.smartinterview.common.util.UserHolder;
import com.smartinterview.entity.ResumeAnalysis;

import com.smartinterview.service.ai.ResumeAiAnalyzerService;
import com.smartinterview.service.ai.ResumeOptimizeService;
import com.smartinterview.service.ResumeAnalysisService;
import com.smartinterview.mapper.ResumeAnalysisMapper;


import com.smartinterview.vo.ResumeDetailVO;
import com.smartinterview.vo.ResumeUploadVO;
import com.smartinterview.vo.ResumeVO;
import dev.langchain4j.service.TokenStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
* @author 32341
* @description 针对表【resume_analysis(简历智能分析表)】的数据库操作Service实现
* @createDate 2026-02-26 16:36:05
*/
@Service
@Slf4j
public class ResumeAnalysisServiceImpl extends ServiceImpl<ResumeAnalysisMapper, ResumeAnalysis>
    implements ResumeAnalysisService {
    @Autowired
    private AliOssUtil aliOssUtil;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    //创建代理对象时，注入接口的bean,
    // JDK 代理是基于接口造替身，替身只认接口，不认你的实现类！
//    @Lazy //延迟注入，在第一次使用时注入
//    @Autowired
//    private ResumeAnalysisService self;
    @Autowired
    private ResumeStateManager resumeStateManager;
    @Autowired
    private ResumeAiAnalyzerService resumeAiAnalyzer;
    @Autowired
    private ResumeOptimizeService resumeOptimizeService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    public ResumeUploadVO upload(MultipartFile file)  {
        if(!file.getOriginalFilename().toLowerCase().endsWith(".pdf")){
            throw new ResumeUploadException("目前仅支持pdf格式的文件上传");
        }
        //上传到OSS
        String fileName= null;
        String fileUrl = null;
        try {

            fileName = UUID.randomUUID()+"-"+file.getOriginalFilename();
            fileUrl = aliOssUtil.upload(file.getBytes(), fileName);
        } catch (IOException e) {
            log.info("简历上传失败：{}",e);
            throw new ResumeUploadException("简历上传阿里云失败");
        }
        log.info("上传成功：{}",fileName);
        //初始化数据库
        Long userId = UserHolder.getUser().getId();
        ResumeAnalysis resumeAnalysis=new ResumeAnalysis();
        resumeAnalysis.setUserId(userId);
        resumeAnalysis.setName(file.getOriginalFilename());
        resumeAnalysis.setFileUrl(fileUrl);
        resumeAnalysis.setCreateTime(LocalDateTime.now());
        resumeAnalysis.setUpdateTime(LocalDateTime.now());
        resumeAnalysis.setStatus(Integer.valueOf(0));
        save(resumeAnalysis);
        Long resumeId=resumeAnalysis.getId();
        //发送到mq
        rabbitTemplate.convertAndSend(RabbitConstants.RESUME_PARSE_EXCHANGE,RabbitConstants.RESUME_PARSE_ROUTING_KEY,resumeId);


        return new ResumeUploadVO(resumeId);
    }

    /**
     * 简历分析
     * @param resumeId
     * @return
     */
    public SseEmitter streamAiAnalysis(Long resumeId){
        //创建SSEEmitter，封装SSE的连接关闭
        SseEmitter emitter=new SseEmitter(300_000L);
        ResumeAnalysis resumeAnalysis=getById(resumeId);
        if(resumeAnalysis==null|| StrUtil.isBlank(resumeAnalysis.getOriginalText())){
            safeSendAndClose(emitter, "简历为空，请稍后重试");
            return emitter;
        }
        // 防重：已分析完成直接返回
        if(resumeAnalysis.getStatus()>=2){
            safeSendAndClose(emitter, "简历已分析完成，请刷新页面查看结果");
            return emitter;
        }
        // 防重：Redis锁，防止并发重复触发
        String lockKey = RedisConstants.RESUME_ANALYZE_LOCK + resumeId;
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", RedisConstants.RESUME_ANALYZE_LOCK_TTL, java.util.concurrent.TimeUnit.MINUTES);
        if(!Boolean.TRUE.equals(locked)){
            safeSendAndClose(emitter, "简历正在分析中，请勿重复提交");
            return emitter;
        }
        final String rawText = resumeAnalysis.getOriginalText();
        String userPrompt = "这是候选人的简历纯文本，请进行诊断并评分：" + rawText;

        AtomicBoolean sseClosed = new AtomicBoolean(false);
        // 全量缓冲：用于 onCompleteResponse 时解析 [SCORE_JSON] 并存库
        StringBuilder fullBuffer = new StringBuilder();
        // 标记是否已遇到 [SCORE_JSON]，遇到后停止向 SSE 推送
        AtomicBoolean scoreMarkerFound = new AtomicBoolean(false);

        TokenStream tokenStream = resumeAiAnalyzer.streamAnalyzeResume(userPrompt);

        tokenStream
                .onPartialResponse(chunk -> {
                    if (sseClosed.get()) return;
                    fullBuffer.append(chunk);
                    // 只要还没遇到标记，继续向前端推送诊断文本
                    if (!scoreMarkerFound.get()) {
                        if (chunk.contains("[SCORE_JSON]")) {
                            String beforeMarker = chunk.substring(0, chunk.indexOf("[SCORE_JSON]"));
                            if (!beforeMarker.isEmpty()) {
                                try { emitter.send(beforeMarker); } catch (Exception e) {
                                    sseClosed.set(true);
                                }
                            }
                            scoreMarkerFound.set(true);
                        } else {
                            try { emitter.send(chunk); } catch (Exception e) {
                                sseClosed.set(true);
                            }
                        }
                    }
                })
                .onError(err -> {
                    log.error("简历流式分析异常", err);
                    try { safeSendAndClose(emitter, "生成异常，请重试"); } catch (Exception ignore) {}
                    // 流异常也要尝试保存已收到的数据
                    String fullContent = fullBuffer.toString();
                    if (!fullContent.isEmpty()) {
                        int markerIdx = fullContent.indexOf("[SCORE_JSON]");
                        String analysisText = markerIdx >= 0 ? fullContent.substring(0, markerIdx).trim() : fullContent;
                        String scoreJson = null;
                        if (markerIdx >= 0) {
                            try {
                                String jsonPart = fullContent.substring(markerIdx + "[SCORE_JSON]".length()).trim();
                                int start = jsonPart.indexOf("{");
                                int end = jsonPart.lastIndexOf("}");
                                if (start != -1 && end != -1) {
                                    cn.hutool.json.JSONObject json = cn.hutool.json.JSONUtil.parseObj(jsonPart.substring(start, end + 1));
                                    scoreJson = json.get("score") != null ? json.get("score").toString() : null;
                                }
                            } catch (Exception ignore) {}
                        }
                        resumeStateManager.updateToTextAndScore(resumeId, analysisText, scoreJson);
                    }
                })
                .onCompleteResponse(response -> {
                    // 不管 SSE 是否断开，都必须执行数据入库
                    try { safeSendAndComplete(emitter, "[DONE]"); } catch (Exception ignore) {}

                    String fullContent = fullBuffer.toString();
                    log.info("简历分析完成，resumeId={}，总字数={}", resumeId, fullContent.length());

                    // 从全量文本中分离：诊断文本 + 评分JSON
                    int markerIdx = fullContent.indexOf("[SCORE_JSON]");
                    String analysisText = markerIdx >= 0 ? fullContent.substring(0, markerIdx).trim() : fullContent;
                    String scoreJson = null;
                    if (markerIdx >= 0) {
                        String jsonPart = fullContent.substring(markerIdx + "[SCORE_JSON]".length()).trim();
                        log.info("评分原始JSON，resumeId={}，内容={}", resumeId, jsonPart);
                        try {
                            int start = jsonPart.indexOf("{");
                            int end = jsonPart.lastIndexOf("}");
                            if (start != -1 && end != -1) {
                                cn.hutool.json.JSONObject json = cn.hutool.json.JSONUtil.parseObj(
                                        jsonPart.substring(start, end + 1));
                                scoreJson = json.get("score") != null ? json.get("score").toString() : null;
                            }
                            log.info("简历评分解析完成，resumeId={}，score={}", resumeId, scoreJson);
                        } catch (Exception ex) {
                            log.error("简历评分JSON解析失败，resumeId={}", resumeId, ex);
                        }
                    } else {
                        log.warn("未找到[SCORE_JSON]标记，resumeId={}，AI输出末尾={}",
                                resumeId, fullContent.substring(Math.max(0, fullContent.length() - 200)));
                    }

                    // 一次性写库：诊断文本 + 评分 + status=2
                    resumeStateManager.updateToTextAndScore(resumeId, analysisText, scoreJson);
                })
                .start();
        return emitter;
    }


    private void safeSendAndClose(SseEmitter emitter, String msg) {
        try {
            emitter.send(msg);
            emitter.complete();
        } catch (Exception ignore) {}
    }

    private void safeSendAndComplete(SseEmitter emitter, String msg) {
        try {
            emitter.send(msg);
        } catch (Exception e) {
            log.debug("SSE 已关闭");
        } finally {
            try {
                emitter.complete();
            } catch (Exception ignore) {
            }
        }
    }
    //前端每两秒查询一个简历状态，status，时打分完成，简历分析成功
    public ResumeDetailVO getResumeDetail(Long resumeId){
        ResumeAnalysis resume=getById(resumeId);
        if(resume==null){
            throw new ResumeNotFindException("简历不存在");
        }
        ResumeDetailVO vo=new ResumeDetailVO();
        vo.setStatus(resume.getStatus());
        //前台轨完成(status>=2)时评分已就绪
        if(resume.getStatus()>=2){
            vo.setScore(resume.getScore());
            vo.setReport(resume.getAiResult());
        }
        return vo;
    }
    public String queryReport(Long resumeId){
        ResumeAnalysis resume = getById(resumeId);
        if(resume.getStatus()<2){
            throw new ResumeAnalysisException("简历尚未处理完成");
        }
        return resume.getAiResult();

    }

    /**
     * 流式生成简历优化建议
     * @param resumeId 简历ID
     * @param jobDescription 目标岗位JD（可选）
     * @return SSE 流式 emitter
     */
    public SseEmitter streamOptimize(Long resumeId, String jobDescription) {
        SseEmitter emitter = new SseEmitter(300_000L);
        ResumeAnalysis resume = getById(resumeId);
        if (resume == null) {
            safeSendAndClose(emitter, "简历不存在");
            return emitter;
        }
        if (resume.getStatus() == null || resume.getStatus() < 2) {
            safeSendAndClose(emitter, "简历尚未分析完成，请稍后重试");
            return emitter;
        }
        if (StrUtil.isBlank(resume.getOriginalText())) {
            safeSendAndClose(emitter, "简历原文为空");
            return emitter;
        }
        if (StrUtil.isBlank(resume.getAiResult())) {
            safeSendAndClose(emitter, "简历诊断报告为空，请先完成简历分析");
            return emitter;
        }

        String originalText = resume.getOriginalText();
        String aiReport = resume.getAiResult();
        String jd = StrUtil.isNotBlank(jobDescription) ? jobDescription : "";

        log.info("开始简历优化，resumeId={}, hasJD={}", resumeId, StrUtil.isNotBlank(jd));

        AtomicBoolean sseClosed = new AtomicBoolean(false);
        StringBuilder fullBuffer = new StringBuilder();

        TokenStream tokenStream = resumeOptimizeService.optimize(originalText, aiReport, jd);

        tokenStream
                .onPartialResponse(chunk -> {
                    if (sseClosed.get()) return;
                    fullBuffer.append(chunk);
                    try {
                        emitter.send(chunk);
                    } catch (Exception e) {
                        sseClosed.set(true);
                    }
                })
                .onError(err -> {
                    log.error("简历优化流式异常, resumeId={}", resumeId, err);
                    try {
                        safeSendAndClose(emitter, "优化生成异常，请重试");
                    } catch (Exception ignore) {}
                })
                .onCompleteResponse(response -> {
                    try {
                        safeSendAndComplete(emitter, "[DONE]");
                    } catch (Exception ignore) {}

                    String suggestion = fullBuffer.toString();
                    log.info("简历优化完成，resumeId={}, 字数={}", resumeId, suggestion.length());

                    // 写入 suggestion 字段
                    try {
                        ResumeAnalysis update = new ResumeAnalysis();
                        update.setId(resumeId);
                        update.setSuggestion(suggestion);
                        update.setUpdateTime(LocalDateTime.now());
                        updateById(update);
                        log.info("优化建议已写入数据库, resumeId={}", resumeId);
                    } catch (Exception e) {
                        log.error("优化建议写入数据库失败, resumeId={}", resumeId, e);
                    }
                })
                .start();

        return emitter;
    }

    /**
     * 查询已生成的简历优化建议
     * @param resumeId 简历ID
     * @return 优化建议文本
     */
    public String queryOptimize(Long resumeId) {
        ResumeAnalysis resume = getById(resumeId);
        if (resume == null) {
            throw new ResumeNotFindException("简历不存在");
        }
        if (resume.getStatus() == null || resume.getStatus() < 2) {
            throw new ResumeAnalysisException("简历尚未分析完成");
        }
        return resume.getSuggestion();
    }



//    public PageResult pageQuery(Integer current, Integer size){
//        IPage<ResumeAnalysis> page=new Page<>(current,size);
//        Long userId=UserHolder.getUser().getId();
//        IPage<ResumeAnalysis> pageResult = lambdaQuery()
//                .eq(ResumeAnalysis::getUserId, userId)
//                .orderByDesc(ResumeAnalysis::getCreateTime)
//                .page(page);
//        List<ResumeAnalysis> resumeAnalysisList=pageResult.getRecords();
//        List<ResumePageVO> list= resumeAnalysisList.stream().map(
//                r->{
//                    ResumePageVO resumePageVO = BeanUtil.copyProperties(r, ResumePageVO.class);
//                    return resumePageVO;
//                }
//        ).collect(Collectors.toList());
//
//     return new PageResult(pageResult.getTotal(),pageResult.getPages(),current,pageResult.getSize(),list);
//    }
    public List<ResumeVO> queryResume(){
        Long userId=UserHolder.getUser().getId();
        LambdaQueryWrapper<ResumeAnalysis> wrapper=new LambdaQueryWrapper<>();
        wrapper.eq(ResumeAnalysis::getUserId,userId)
                .orderByDesc(ResumeAnalysis::getCreateTime);
         List<ResumeVO> list=list(wrapper).stream()
                 //加上{} return也必须加上；
                 .map(r->{return BeanUtil.copyProperties(r,ResumeVO.class);
                 }).collect(Collectors.toList());
         return list;
    }

    @Override
    public void logicalDelete(Long resumeId) {
        ResumeAnalysis resume=getById(resumeId);
        if(resume==null){
            throw new ResumeNotFindException("简历不存在");
        }
        removeById(resumeId);//update is_delete=1
    }
}






