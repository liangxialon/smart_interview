package com.smartinterview.service;

public interface AudioService {
    /**
     * 语音识别（ASR）：音频 → 文本
     * @param audioData 音频字节数据
     * @return 识别出的文本
     */
    String convertToText(byte[] audioData);
}
