package org.example;

import com.google.common.collect.ImmutableMap;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.model.output.Response;
import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.EdgeAction;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.langchain4j.tool.ToolNode;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * LangGraph + LangChain4j 集成：
 * <ul>
 *   <li>复用 {@link LangChain4jConfig} 里的 ChatLanguageModel</li>
 *   <li>复用 {@link LangChain4jConfig} 里的 ChatMemoryProvider（Redis 持久化多轮记忆）</li>
 *   <li>复用 {@link LangChain4jConfig} 里的 ContentRetriever（Chroma 向量库 RAG）</li>
 *   <li>复用 {@link AgentTool}（@Tool 注解工具，被官方 ToolNode 包装执行）</li>
 * </ul>
 *
 * <p>Graph 结构：</p>
 * <pre>
 *   START -> retrieve(可选 RAG) -> agent(LLM+ToolCall)
 *                                   ├── 有 tool_calls -> tools(执行) -> agent (循环)
 *                                   └── 无 tool_calls -> END
 * </pre>
 */
@Component
public class LangGraphInterGateLangChain4j {

    private static final Logger log = LoggerFactory.getLogger(LangGraphInterGateLangChain4j.class);

    private static final String SYSTEM_PROMPT =
            "你是一个资深的 Java 后端专家和架构师。" +
            "请用专业、严谨且通俗易懂的语言来回答研发人员提出的技术问题。" +
            "必须用中文回复。";

    @Value("${agent.rag.enabled:false}")
    private boolean ragEnabled;

    private final ChatLanguageModel model;
    private final StreamingChatLanguageModel streamingModel;
    private final ChatMemoryProvider memoryProvider;
    private final Optional<ContentRetriever> contentRetrieverOpt;
    private final ToolNode tools;
    private final StateGraph<MessageState<ChatMessage>> workflow;

    /**
     * 构造器注入：全部复用 LangChain4jConfig 中已配置好的 Bean。
     * <p>
     * 设置 agent.rag.enabled=true 可启用 RAG 向量检索，默认 false 不启用。
     */
    public LangGraphInterGateLangChain4j(ChatLanguageModel chatLanguageModel,
                                         StreamingChatLanguageModel streamingChatLanguageModel,
                                         ChatMemoryProvider memoryProvider,
                                         @Autowired(required = false) @Qualifier("contentRetriever") ContentRetriever contentRetriever,
                                         AgentTool agentTool) {
        this.model = chatLanguageModel;
        this.streamingModel = streamingChatLanguageModel;
        this.memoryProvider = memoryProvider;
        this.contentRetrieverOpt = Optional.ofNullable(contentRetriever);
        this.tools = ToolNode.of(agentTool);

        StateSerializer<MessageState<ChatMessage>> stateSerializer =
                new InMemoryStateSerializer<>(MessageState::new);

        // 1) retrieve 节点：可选地从向量库检索相关知识，注入到 System 消息中
        NodeAction<MessageState<ChatMessage>> retrieve = state -> {
            List<ChatMessage> messages = state.messages();
            String lastUserText = messages.stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .reduce((a, b) -> b)
                    .map(UserMessage::singleText)
                    .orElse("");

            StringBuilder systemBuilder = new StringBuilder(SYSTEM_PROMPT);
            if (ragEnabled && contentRetrieverOpt.isPresent() && lastUserText != null && !lastUserText.isEmpty()) {
                log.info("RAG 已启用，开始检索向量数据库...");
                List<Content> docs = contentRetrieverOpt.get().retrieve(Query.from(lastUserText));
                if (docs != null && !docs.isEmpty()) {
                    String context = docs.stream()
                            .map(c -> c.textSegment().text())
                            .collect(Collectors.joining("\n---\n"));
                    systemBuilder.append("\n\n【参考资料】\n").append(context);
                    log.info("RAG 召回 {} 条文档", docs.size());
                } else {
                    log.info("RAG 未召回相关文档");
                }
            } else {
                log.info("RAG 已禁用，跳过向量检索");
            }
            SystemMessage sysMsg = SystemMessage.from(systemBuilder.toString());
            return ImmutableMap.of(MessageState.MESSAGES_KEY, (Object) sysMsg);
        };

        // 2) agent 节点：调用 LLM，如果模型要求调用工具则返回带 tool_calls 的 AiMessage
        NodeAction<MessageState<ChatMessage>> callModel = state -> {
            List<ChatMessage> messages = state.messages();
            List<dev.langchain4j.agent.tool.ToolSpecification> toolSpecs = tools.toolSpecifications();

            AiMessage aiMessage;
            if (toolSpecs == null || toolSpecs.isEmpty()) {
                aiMessage = model.generate(messages).content();
            } else {
                aiMessage = model.generate(messages, toolSpecs).content();
            }
            log.info("agent 输出：hasToolCalls={}, text={}",
                    aiMessage.hasToolExecutionRequests(), aiMessage.text());
            return ImmutableMap.of(MessageState.MESSAGES_KEY, (Object) aiMessage);
        };

        // 3) route 节点：判断是否需要进入 tools 分支
        EdgeAction<MessageState<ChatMessage>> routeMessage = state -> {
            Optional<ChatMessage> lastMessage = state.lastMessage();
            if (!lastMessage.isPresent()) {
                throw new IllegalStateException("last message not found!");
            }
            ChatMessage message = lastMessage.get();
            if (message instanceof AiMessage && ((AiMessage) message).hasToolExecutionRequests()) {
                return "next";
            }
            return "exit";
        };

        // 4) tools 节点：逐个执行 LLM 请求的工具调用
        AsyncNodeAction<MessageState<ChatMessage>> invokeTool = state -> {
            Optional<ChatMessage> lastMessage = state.lastMessage();
            if (!lastMessage.isPresent()) {
                CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
                f.completeExceptionally(new IllegalStateException("last message not found!"));
                return f;
            }

            if (lastMessage.get() instanceof AiMessage) {
                AiMessage lastAiMessage = (AiMessage) lastMessage.get();
                List<dev.langchain4j.agent.tool.ToolExecutionRequest> requests =
                        lastAiMessage.toolExecutionRequests();

                List<ChatMessage> results = new ArrayList<>();
                for (dev.langchain4j.agent.tool.ToolExecutionRequest req : requests) {
                    log.info("调用工具：{}(args={})", req.name(), req.arguments());
                    Optional<ToolExecutionResultMessage> result = tools.execute(req);
                    result.ifPresent(results::add);
                }
                return CompletableFuture.completedFuture(
                        ImmutableMap.<String, Object>of(MessageState.MESSAGES_KEY, results));
            }

            CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("invalid last message"));
            return f;
        };

        try {
            StateGraph<MessageState<ChatMessage>> g = new MessagesStateGraph<>(stateSerializer);
            g.addNode("retrieve", node_async(retrieve));
            g.addNode("agent", node_async(callModel));
            g.addNode("tools", invokeTool);
            g.addEdge(START, "retrieve");
            g.addEdge("retrieve", "agent");
            g.addConditionalEdges("agent",
                    edge_async(routeMessage),
                    ImmutableMap.of("next", "tools", "exit", END));
            g.addEdge("tools", "agent");
            this.workflow = g;
        } catch (GraphStateException e) {
            throw new RuntimeException("Failed to build LangGraph workflow", e);
        }
    }

    /**
     * 对外聊天入口：自动装配多轮记忆 + RAG + 工具调用。
     *
     * @param userMessage 用户当前输入
     * @param memoryId    会话 ID，用于区分不同用户的历史记忆（可为 null，将使用默认）
     * @return 最终的对话状态（包含完整消息历史）
     */
    public Optional<MessageState<ChatMessage>> chat(String userMessage, String memoryId) throws Exception {
        Object id = (memoryId == null || memoryId.isEmpty()) ? "default" : memoryId;
        ChatMemory memory = memoryProvider.get(id);

        List<ChatMessage> history = new ArrayList<>(memory.messages());
        history.add(UserMessage.from(userMessage));

        Map<String, Object> input = ImmutableMap.<String, Object>of(
                MessageState.MESSAGES_KEY, (Object) history);

        Optional<MessageState<ChatMessage>> result = workflow.compile().invoke(input);

        if (result.isPresent()) {
            List<ChatMessage> finalMessages = result.get().messages();
            for (ChatMessage m : finalMessages) {
                memory.add(m);
            }
        }
        return result;
    }

    /** 简化版：不带 memoryId 的单轮对话（仅用于测试） */
    public Optional<MessageState<ChatMessage>> chat(String userMessage) throws Exception {
        return chat(userMessage, null);
    }

    /**
     * 流式聊天：通过 LangGraph 的 stream() 接口，把每个节点的思考过程收集为 ThoughtItem 列表。
     * <p>
     * 每个节点执行完后都会产生一个 ThoughtItem，前端可以逐项展示：
     * <ol>
     *   <li>retrieve：RAG 召回的参考资料</li>
     *   <li>agent：LLM 的思考输出（是否决定调用工具）</li>
     *   <li>tools：工具执行结果</li>
     *   <li>最终 agent 返回的文本回复（finalAnswer=true）</li>
     * </ol>
     *
     * @param userMessage 用户输入
     * @param memoryId    会话 ID
     * @return 思考过程列表（按节点执行顺序排列）
     */
    public List<ThoughtItem> streamChat(String userMessage, String memoryId) throws Exception {
        Object id = (memoryId == null || memoryId.isEmpty()) ? "default" : memoryId;
        ChatMemory memory = memoryProvider.get(id);

        List<ChatMessage> history = new ArrayList<>(memory.messages());
        history.add(UserMessage.from(userMessage));

        Map<String, Object> input = ImmutableMap.<String, Object>of(
                MessageState.MESSAGES_KEY, (Object) history);

        AsyncGenerator<NodeOutput<MessageState<ChatMessage>>> generator =
                workflow.compile().stream(input);

        List<ThoughtItem> thoughts = new ArrayList<>();
        long startMs = System.currentTimeMillis();

        try {
            for (NodeOutput<MessageState<ChatMessage>> output : generator) {
                String nodeName = output.node();
                MessageState<ChatMessage> state = output.state();
                List<ChatMessage> messages = state != null ? state.messages() : new ArrayList<>();

                long elapsed = System.currentTimeMillis() - startMs;
                ThoughtItem item = buildThoughtItem(nodeName, messages, elapsed);
                thoughts.add(item);
                log.info("Thought[{}]: {}", nodeName, item);
            }
        } catch (Exception e) {
            log.error("streamChat 出错", e);
        }

        Optional<MessageState<ChatMessage>> finalState = workflow.compile().invoke(input);
        if (finalState.isPresent()) {
            List<ChatMessage> finalMessages = finalState.get().messages();
            for (ChatMessage m : finalMessages) {
                memory.add(m);
            }
        }

        return thoughts;
    }

    /**
     * 简化版流式聊天（不带 memoryId）。
     */
    public List<ThoughtItem> streamChat(String userMessage) throws Exception {
        return streamChat(userMessage, null);
    }

    private ThoughtItem buildThoughtItem(String nodeName, List<ChatMessage> messages, long durationMs) {
        switch (nodeName) {
            case "retrieve": {
                String lastUserText = messages.stream()
                        .filter(UserMessage.class::isInstance)
                        .map(UserMessage.class::cast)
                        .reduce((a, b) -> b)
                        .map(UserMessage::singleText)
                        .orElse("");
                boolean hasSystem = messages.stream().anyMatch(SystemMessage.class::isInstance);
                String actionText;
                if (!ragEnabled) {
                    actionText = "RAG 已禁用，跳过向量检索";
                } else if (hasSystem) {
                    actionText = "RAG 检索成功，注入参考资料";
                } else {
                    actionText = "RAG 已启用，但未召回相关文档";
                }
                return new ThoughtItem("retrieve", actionText, lastUserText, messages, false, durationMs);
            }
            case "agent": {
                Optional<ChatMessage> lastMsg = messages.isEmpty() ? Optional.empty() : Optional.of(messages.get(messages.size() - 1));
                if (lastMsg.isPresent() && lastMsg.get() instanceof AiMessage) {
                    AiMessage ai = (AiMessage) lastMsg.get();
                    boolean hasToolCalls = ai.hasToolExecutionRequests();
                    boolean isFinal = !hasToolCalls;
                    String summary;
                    if (hasToolCalls) {
                        StringBuilder sb = new StringBuilder("决定调用 ");
                        for (dev.langchain4j.agent.tool.ToolExecutionRequest r : ai.toolExecutionRequests()) {
                            sb.append(r.name()).append("(").append(r.arguments()).append(") ");
                        }
                        summary = sb.toString().trim();
                    } else {
                        summary = ai.text();
                    }
                    return new ThoughtItem("agent",
                            hasToolCalls ? "LLM 决策：需要调用工具" : "LLM 输出最终回复",
                            summary, messages, isFinal, durationMs);
                }
                return new ThoughtItem("agent", "LLM 处理中", "", messages, false, durationMs);
            }
            case "tools": {
                StringBuilder sb = new StringBuilder();
                for (ChatMessage m : messages) {
                    if (m instanceof ToolExecutionResultMessage) {
                        ToolExecutionResultMessage trm = (ToolExecutionResultMessage) m;
                        sb.append("[").append(trm.toolName()).append("] = ").append(trm.text()).append("; ");
                    }
                }
                return new ThoughtItem("tools", "执行工具调用",
                        sb.toString().trim(), messages, false, durationMs);
            }
            default:
                return new ThoughtItem(nodeName, "节点执行", "", messages, false, durationMs);
        }
    }

    /**
     * 流式聊天（TokenStream 版本）：与 chat() 类似，但支持 token 级别的流式输出。
     * <p>
     * 此方法使用 LangGraph 的 stream() 接口，每个节点完成后会通过 TokenStream 输出。
     * 最终 agent 节点的回复会以 token 级别流式输出。
     * <p>
     * 注：langchain4j 0.34.0 的 {@link TokenStream} 是接口（没有 builder），
     * 因此这里返回一个轻量的自定义实现，内部在 start() 时把最终 AI 文本按字符
     * 通过 onNext 回调逐字推出去。
     *
     * @param userMessage 用户输入
     * @param memoryId    会话 ID
     * @return TokenStream，可用于 SSE 或 WebFlux 流式输出
     */
    public TokenStream tokenStreamChat(String userMessage, String memoryId) throws Exception {
        Object id = (memoryId == null || memoryId.isEmpty()) ? "default" : memoryId;
        ChatMemory memory = memoryProvider.get(id);

        List<ChatMessage> history = new ArrayList<>(memory.messages());
        history.add(UserMessage.from(userMessage));

        Map<String, Object> input = ImmutableMap.<String, Object>of(
                MessageState.MESSAGES_KEY, (Object) history);

        // 先同步执行图，获取最终状态（包括工具调用等）
        Optional<MessageState<ChatMessage>> finalState = workflow.compile().invoke(input);

        // 保存记忆
        if (finalState.isPresent()) {
            List<ChatMessage> finalMessages = finalState.get().messages();
            for (ChatMessage m : finalMessages) {
                memory.add(m);
            }
        }

        // 找到最后的 AI 回复文本
        final String replyText = finalState
                .map(MessageState::messages)
                .orElse(new ArrayList<>())
                .stream()
                .filter(AiMessage.class::isInstance)
                .map(AiMessage.class::cast)
                .map(AiMessage::text)
                .filter(java.util.Objects::nonNull)
                .reduce((a, b) -> b)
                .orElse("");

        return new GraphAgentTokenStream(replyText);
    }

    /**
     * 简化版 TokenStream 聊天（不带 memoryId）。
     */
    public TokenStream tokenStreamChat(String userMessage) throws Exception {
        return tokenStreamChat(userMessage, null);
    }

    /**
     * 针对 langchain4j 0.34.0 {@link TokenStream} 接口的轻量实现：
     * 在 {@link #start()} 启动后，把预先得到的 AI 回复文本按字符通过
     * {@link #onNext} 回调逐字推送给订阅方，模拟 token 级的流式效果。
     */
    private static class GraphAgentTokenStream implements TokenStream {

        private final String replyText;
        private final AtomicBoolean started = new AtomicBoolean(false);

        private Consumer<List<Content>> onRetrievedHandler;
        private Consumer<String> onNextHandler;
        private Consumer<Response<AiMessage>> onCompleteHandler;
        private Consumer<Throwable> onErrorHandler;
        private boolean ignoreErrors;

        GraphAgentTokenStream(String replyText) {
            this.replyText = replyText == null ? "" : replyText;
        }

        @Override
        public TokenStream onRetrieved(Consumer<List<Content>> handler) {
            this.onRetrievedHandler = handler;
            return this;
        }

        @Override
        public TokenStream onNext(Consumer<String> handler) {
            this.onNextHandler = handler;
            return this;
        }

        @Override
        public TokenStream onComplete(Consumer<Response<AiMessage>> handler) {
            this.onCompleteHandler = handler;
            return this;
        }

        @Override
        public TokenStream onError(Consumer<Throwable> handler) {
            this.onErrorHandler = handler;
            return this;
        }

        @Override
        public TokenStream ignoreErrors() {
            this.ignoreErrors = true;
            return this;
        }

        @Override
        public void start() {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            new Thread(() -> {
                try {
                    if (onNextHandler != null) {
                        for (int i = 0; i < replyText.length(); i++) {
                            String ch = String.valueOf(replyText.charAt(i));
                            onNextHandler.accept(ch);
                            Thread.sleep(30L);
                        }
                    }
                    if (onCompleteHandler != null) {
                        AiMessage aiMessage = AiMessage.from(replyText);
                        onCompleteHandler.accept(Response.from(aiMessage));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    if (onErrorHandler != null) {
                        onErrorHandler.accept(t);
                    } else if (!ignoreErrors) {
                        log.error("TokenStream 执行出错", t);
                    }
                }
            }, "graph-agent-token-stream").start();
        }
    }
}