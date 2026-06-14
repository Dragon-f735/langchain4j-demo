package org.example;

import com.google.common.collect.ImmutableMap;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.AppenderChannel;
import org.bsc.langgraph4j.state.Channel;

import java.util.*;

/**
 * 消息状态：用于在 LangGraph 节点之间传递对话消息列表。
 * <p>
 * 配合 {@link InMemoryStateSerializer} 使用，绕开 langchain4j ChatMessage
 * 未实现 {@link java.io.Serializable} 的限制。
 *
 * @param <T> 消息类型（通常为 {@link dev.langchain4j.data.message.ChatMessage}）
 */
public class MessageState<T> extends AgentState {

    public static final String MESSAGES_KEY = "messages";

    public static final Map<String, Channel<?>> SCHEMA = ImmutableMap.of(
            MESSAGES_KEY, AppenderChannel.of(() -> new ArrayList<>())
    );

    public MessageState(Map<String, Object> initData) {
        super(initData);
    }

    public List<T> messages() {
        return this.<List<T>>value(MESSAGES_KEY)
                .orElseThrow(() -> new RuntimeException("message not found"));
    }

    public Optional<T> lastMessage() {
        List<T> messages = messages();
        return ( messages.isEmpty() ) ?
                Optional.empty() :
                Optional.of(messages.get( messages.size() - 1 ));
    }

    public Optional<T> lastMinus(int n) {
        List<T> messages = messages();
        if ( n < 0 || messages.isEmpty() )  {
            return Optional.empty();
        }
        int index = messages.size() - n - 1;
        return ( index < 0 ) ?
                Optional.empty() :
                Optional.of(messages.get(index));
    }
}
