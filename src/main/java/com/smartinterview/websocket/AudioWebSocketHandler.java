package com.smartinterview.websocket;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.alibaba.dashscope.common.ResultCallback;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.smartinterview.common.util.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实时语音识别 WebSocket 处理器
 * 前端建立 WebSocket 连接后，持续发送 PCM 二进制分片
 * 后端通过 DashScope paraformer-realtime-v2 实时识别，将结果推回前端
 *
 * 连接地址：ws://host/ws/audio?token=xxx
 * 前端发送：BinaryMessage（16kHz 单通道 PCM 音频分片）
 * 后端推送：TextMessage（识别出的文本片段）
 * 关闭信号：前端发送 TextMessage "END" 表示音频结束
 */
@Component
@Slf4j
public class AudioWebSocketHandler extends BinaryWebSocketHandler {

    @Value("${ai.api-key}")
    private String apiKey;

    @Autowired
    @Qualifier("voiceModelName")
    private String voiceModelName;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /** 每个 WebSocket 会话对应一个 DashScope Recognition 实例 */
    private final ConcurrentHashMap<String, Recognition> sessionRecognitions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StringBuilder> sessionTextBuffer = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // 从 URL 参数中提取 token 并校验
        String query = session.getUri().getQuery();
        if (query == null || !query.startsWith("token=")) {
            log.warn("WebSocket 连接缺少 token 参数");
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        String token = query.substring("token=".length());
        // 校验 token（复用 Redis 中的用户 token）
        String userId = stringRedisTemplate.opsForValue().get("smartinterview:user:token:" + token);
        if (userId == null) {
            log.warn("WebSocket token 无效或已过期");
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }

        String sessionId = session.getId();
        log.info("语音识别 WebSocket 已建立, sessionId={}, userId={}", sessionId, userId);

        // 创建 DashScope Recognition 实例
        try {
            RecognitionParam param = RecognitionParam.builder()
                    .apiKey(apiKey)
                    .model(voiceModelName)
                    .format("pcm")
                    .sampleRate(16000)
                    .build();

            Recognition recognition = new Recognition();
            sessionRecognitions.put(sessionId, recognition);
            sessionTextBuffer.put(sessionId, new StringBuilder());

            // 启动流式识别
            // 泛型必须为 RecognitionResult
            recognition.call(param, new ResultCallback<RecognitionResult>() {
                @Override
                public void onEvent(RecognitionResult result) {
                    // 阿里云返回的实时识别结果，推送给前端
                    try {
                        if (result != null && result.getSentence() != null && session.isOpen()) {
                            String text = result.getSentence().getText();
                            if (text != null && !text.isBlank()) {
                                session.sendMessage(new TextMessage(text));
                            }
                        }
                    } catch (IOException e) {
                        log.error("推送识别结果失败, sessionId={}", session.getId(), e);
                    }
                }

                @Override
                public void onComplete() {
                    log.info("实时识别完成, sessionId={}", session.getId());
                }

                @Override
                public void onError(Exception e) {
                    log.error("实时识别发生错误, sessionId={}", session.getId(), e);
                }
            });
        } catch (Exception e) {
            log.error("DashScope API Key 未配置", e);
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        // 收到前端发来的 PCM 音频分片，转发给 DashScope
        String sessionId = session.getId();
        Recognition recognition = sessionRecognitions.get(sessionId);
        if (recognition == null) {
            log.warn("Recognition 实例不存在, sessionId={}", sessionId);
            return;
        }

        byte[] pcmData = message.getPayload().array();
        try {
            // 方案 A：直接传入 message.getPayload()（推荐）
            recognition.sendAudioFrame(message.getPayload());
        } catch (Exception e) {
            log.error("发送音频帧失败, sessionId={}", sessionId, e);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 前端发送 "END" 表示音频采集结束
        String payload = message.getPayload();
        String sessionId = session.getId();

        if ("END".equals(payload)) {
            log.info("收到音频结束信号, sessionId={}", sessionId);
            Recognition recognition = sessionRecognitions.get(sessionId);
            if (recognition != null) {
                try {
                    recognition.stop();
                } catch (Exception e) {
                    log.error("停止识别失败, sessionId={}", sessionId, e);
                }
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = session.getId();
        log.info("语音识别 WebSocket 已关闭, sessionId={}, status={}", sessionId, status);

        // 清理资源
        Recognition recognition = sessionRecognitions.remove(sessionId);
        sessionTextBuffer.remove(sessionId);
        if (recognition != null) {
            try {
                recognition.stop();
            } catch (Exception e) {
                log.debug("关闭 Recognition 异常（可忽略）", e);
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket 传输异常, sessionId={}", session.getId(), exception);
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.SERVER_ERROR);
            }
        } catch (IOException e) {
            log.debug("关闭异常 WebSocket 失败", e);
        }
    }
}
