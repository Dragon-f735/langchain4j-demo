package org.example;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.AgentStateFactory;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于「类型+文本」手写序列化的 {@link StateSerializer} 实现。
 * <p>
 * langchain4j 的 {@link ChatMessage} 实现未实现 {@link java.io.Serializable}，
 * 默认的 ObjectStreamStateSerializer 会抛 {@link java.io.NotSerializableException}。
 * <p>
 * 本实现把每个 ChatMessage 按 {@code type + text} + 附加字段的方式写进字节流，
 * 读回时按对应类型重新构造实例，完全绕开 Java 原生序列化机制。
 *
 * @param <State> 具体的 State 类型（如 MessageState）
 */
public class InMemoryStateSerializer<State extends AgentState> extends StateSerializer<State> {

    private static final String MESSAGES_KEY = "messages";
    private static final int FORMAT_VERSION = 1;

    public InMemoryStateSerializer(AgentStateFactory<State> factory) {
        super(factory);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void write(State state, ObjectOutput out) throws IOException {
        out.writeInt(FORMAT_VERSION);
        Map<String, Object> data = state.data();

        Object rawMessages = data.get(MESSAGES_KEY);
        List<ChatMessage> messages = rawMessages == null ? new ArrayList<>() : (List<ChatMessage>) rawMessages;

        out.writeInt(messages.size());
        for (ChatMessage msg : messages) {
            writeChatMessage(msg, out);
        }
    }

    @Override
    public State read(ObjectInput in) throws IOException, ClassNotFoundException {
        int version = in.readInt();
        if (version != FORMAT_VERSION) {
            throw new IOException("Unsupported serializer version: " + version);
        }

        int size = in.readInt();
        List<ChatMessage> messages = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            messages.add(readChatMessage(in));
        }

        Map<String, Object> data = new HashMap<>();
        data.put(MESSAGES_KEY, messages);
        return stateOf(data);
    }

    private void writeChatMessage(ChatMessage msg, ObjectOutput out) throws IOException {
        out.writeUTF(msg.type().name());
        out.writeUTF(msg.text() == null ? "" : msg.text());

        switch (msg.type()) {
            case USER: {
                UserMessage um = (UserMessage) msg;
                out.writeUTF(um.name() == null ? "" : um.name());
                break;
            }
            case TOOL_EXECUTION_RESULT: {
                ToolExecutionResultMessage trm = (ToolExecutionResultMessage) msg;
                out.writeUTF(trm.id() == null ? "" : trm.id());
                out.writeUTF(trm.toolName() == null ? "" : trm.toolName());
                break;
            }
            case AI: {
                AiMessage am = (AiMessage) msg;
                List<ToolExecutionRequest> reqs = am.toolExecutionRequests();
                int reqSize = reqs == null ? 0 : reqs.size();
                out.writeInt(reqSize);
                for (int i = 0; i < reqSize; i++) {
                    ToolExecutionRequest r = reqs.get(i);
                    out.writeUTF(r.id() == null ? "" : r.id());
                    out.writeUTF(r.name() == null ? "" : r.name());
                    out.writeUTF(r.arguments() == null ? "" : r.arguments());
                }
                break;
            }
            case SYSTEM:
            default:
                break;
        }
    }

    private ChatMessage readChatMessage(ObjectInput in) throws IOException {
        String type = in.readUTF();
        String text = in.readUTF();

        switch (type) {
            case "USER": {
                String name = in.readUTF();
                return name.isEmpty() ? UserMessage.from(text) : UserMessage.from(name, text);
            }
            case "TOOL_EXECUTION_RESULT": {
                String id = in.readUTF();
                String toolName = in.readUTF();
                return new ToolExecutionResultMessage(id, toolName, text);
            }
            case "AI": {
                int reqSize = in.readInt();
                List<ToolExecutionRequest> reqs = new ArrayList<>(reqSize);
                for (int i = 0; i < reqSize; i++) {
                    String rid = in.readUTF();
                    String rname = in.readUTF();
                    String rargs = in.readUTF();
                    reqs.add(ToolExecutionRequest.builder()
                            .id(rid.isEmpty() ? null : rid)
                            .name(rname)
                            .arguments(rargs)
                            .build());
                }
                if (!reqs.isEmpty()) {
                    if (text != null && !text.isEmpty()) {
                        return AiMessage.from(text, reqs);
                    } else {
                        return AiMessage.from(reqs);
                    }
                } else {
                    return AiMessage.from(text != null ? text : "");
                }
            }
            case "SYSTEM":
            default:
                return SystemMessage.from(text != null ? text : "");
        }
    }
}
