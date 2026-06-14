package org.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 存储 LLM 返回的 reasoning（思考过程）内容。
 * <p>
 * 由于 langchain4j 0.34.0 的 AiMessage 不支持 reasoning 字段，
 * 我们通过 OkHttp 拦截器获取原始响应中的 reasoning，并存入此服务。
 */
@Service
public class ReasoningService {

    private static final Logger log = LoggerFactory.getLogger(ReasoningService.class);

    /** 存储每个会话 ID 对应的 reasoning 内容 */
    private final Map<String, String> reasoningStore = new ConcurrentHashMap<>();

    /** 存储当前正在处理的会话 ID（用于拦截器关联） */
    private final ThreadLocal<String> currentSessionId = new ThreadLocal<>();

    /**
     * 设置当前会话 ID（在调用 LLM 之前调用）
     */
    public void setCurrentSessionId(String sessionId) {
        currentSessionId.set(sessionId);
    }

    /**
     * 获取当前会话 ID
     */
    public String getCurrentSessionId() {
        return currentSessionId.get();
    }

    /**
     * 存储 reasoning 内容
     */
    public void saveReasoning(String sessionId, String reasoning) {
        if (sessionId != null && reasoning != null && !reasoning.isEmpty()) {
            reasoningStore.put(sessionId, reasoning);
            log.debug("存储 reasoning for session {}: {}", sessionId, reasoning.substring(0, Math.min(100, reasoning.length())));
        }
    }

    /**
     * 获取 reasoning 内容
     */
    public String getReasoning(String sessionId) {
        return reasoningStore.get(sessionId);
    }

    /**
     * 清除 reasoning 内容
     */
    public void clearReasoning(String sessionId) {
        reasoningStore.remove(sessionId);
    }

    /**
     * 清除所有 reasoning 内容
     */
    public void clearAll() {
        reasoningStore.clear();
    }
}
