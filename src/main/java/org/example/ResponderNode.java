package org.example;

import com.google.common.collect.ImmutableMap;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.List;
import java.util.Map;

public class ResponderNode implements NodeAction<MessageState> {

    @Override
    public Map<String, Object> apply(MessageState state) {
        System.out.println("ResponderNode executing. Current messages: " + state.messages());
        List<String> currentMessages = state.messages();
        if (currentMessages.contains("Hello from GreeterNode!")) {
            return ImmutableMap.of(MessageState.MESSAGES_KEY, "Acknowledged greeting!");
        }
        return ImmutableMap.of(MessageState.MESSAGES_KEY, "No greeting found.");
    }
}
