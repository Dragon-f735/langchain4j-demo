package org.example;

import com.google.common.collect.ImmutableMap;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.Map;

public class GreeterNode implements NodeAction<MessageState> {
    @Override
    public Map<String, Object> apply(MessageState messageState) throws Exception {
        System.out.println("GreeterNode executing. Current messages: " + messageState.messages());
        return ImmutableMap.of(MessageState.MESSAGES_KEY, "Hello from GreeterNode!");
    }
}
