package org.example;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 承载 LangGraph 中单个节点的执行结果（思考过程的一个步骤）。
 * <p>
 * 每个节点执行完后，都会产出一个 ThoughtItem 推送给前端，
 * 让用户能看到 Agent 的完整推理链路。
 */
public class ThoughtItem {

    /** 节点名称，如 "retrieve"、"agent"、"tools" */
    private final String node;

    /** 该节点的动作描述，如 "调用 LLM"、"执行工具"、"RAG 检索" */
    private final String action;

    /** 该节点产出的关键内容（如 LLM 的文本回复、工具调用结果、RAG 召回摘要） */
    private final String content;

    /** 节点执行结束时的完整消息快照（用于调试，不对外暴露） */
    @JsonIgnore
    private final List<ChatMessage> messagesSnapshot;

    /** 是否为最终节点（agent 返回最终回复、无需工具调用时） */
    private final boolean finalAnswer;

    /** 耗时（毫秒），-1 表示未统计 */
    private final long durationMs;

    public ThoughtItem(String node, String action, String content,
                       List<ChatMessage> messagesSnapshot, boolean finalAnswer, long durationMs) {
        this.node = node;
        this.action = action;
        this.content = content;
        this.messagesSnapshot = messagesSnapshot;
        this.finalAnswer = finalAnswer;
        this.durationMs = durationMs;
    }

    public String getNode() {
        return node;
    }

    public String getAction() {
        return action;
    }

    public String getContent() {
        return content;
    }

    @JsonIgnore
    public List<ChatMessage> getMessagesSnapshot() {
        return messagesSnapshot;
    }

    public boolean isFinalAnswer() {
        return finalAnswer;
    }

    public long getDurationMs() {
        return durationMs;
    }

    @Override
    public String toString() {
        return "[" + node + "] " + action + " => " +
                (content == null ? "" : content.length() > 80 ? content.substring(0, 80) + "..." : content);
    }
}
