package org.example;

import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.serializer.StateSerializer;

public class MessagesStateGraph<T> extends StateGraph< MessageState<T>> {

    /**
     * Constructs a new instance of {@code MessagesStateGraph}.
     *
     * @param stateSerializer the serializer for messages states, must not be null
     */
    public MessagesStateGraph( StateSerializer<MessageState<T>> stateSerializer) {
        super(MessageState.SCHEMA, stateSerializer);
    }

    /**
     * Default constructor that initializes a new instance of {@link MessagesStateGraph}.
     * This constructor uses the default schema and constructor from the base class.
     */
    public MessagesStateGraph() {
        super( MessageState.SCHEMA, MessageState::new);
    }
}
