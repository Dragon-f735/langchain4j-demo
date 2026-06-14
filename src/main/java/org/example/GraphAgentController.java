package org.example;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.service.TokenStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 基于 LangGraph 的 Agent 对话入口。
 * 复用 {@link LangGraphInterGateLangChain4j} 中已经组合好的
 * ChatMemory（Redis 多轮记忆）、ContentRetriever（Chroma RAG）、ToolNode 工具调用。
 */
@RestController
@RequestMapping("/graph")
public class GraphAgentController {

    @Autowired
    private LangGraphInterGateLangChain4j graphAgent;

    /**
     * 与基于 LangGraph 的 Agent 对话（同步返回完整消息历史）。
     *
     * @param msg      用户输入
     * @param memoryId 会话 ID，同一个 ID 会共享历史上下文（多轮）
     * @return 最终的完整消息列表
     */
    @GetMapping("/ask")
    public List<String> ask(@RequestParam("msg") String msg,
                            @RequestParam(value = "memoryId", required = false) String memoryId) throws Exception {

        Optional<MessageState<ChatMessage>> result = graphAgent.chat(msg, memoryId);

        return result.map(state -> state.messages().stream()
                .map(Object::toString)
                .collect(Collectors.toList()))
                .orElseThrow(() -> new RuntimeException("Agent 无响应"));
    }

    /**
     * 只取最终 AI 回复文本（更贴近 QAQueryAgent 的使用习惯）。
     */
    @GetMapping("/answer")
    public String answer(@RequestParam("msg") String msg,
                         @RequestParam(value = "memoryId", required = false) String memoryId) throws Exception {

        Optional<MessageState<ChatMessage>> result = graphAgent.chat(msg, memoryId);
        if (!result.isPresent()) {
            throw new RuntimeException("Agent 无响应");
        }

        List<ChatMessage> messages = result.get().messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m instanceof dev.langchain4j.data.message.AiMessage) {
                return ((dev.langchain4j.data.message.AiMessage) m).text();
            }
        }
        return "(无 AI 回复)";
    }

    /**
     * 流式返回 Agent 的最终 AI 回复（TokenStream 版）。
     * <p>
     * 与 {@link AiController#ask} 保持一致：利用 Flux.create 把 LangChain4j 的 TokenStream 回调
     * 揉进 WebFlux 的响应式管道里，实现 token 级别的实时推送。
     *
     * @param msg      用户输入
     * @param memoryId 会话 ID
     * @return Flux<String>，每一个元素就是一个 token（字符），前端可以逐字渲染
     */
    @GetMapping(value = "/stream", produces = "text/html;charset=utf-8")
    public Flux<String> stream(@RequestParam("msg") String msg,
                               @RequestParam(value = "memoryId", required = false) String memoryId) throws Exception {

        TokenStream tokenStream = graphAgent.tokenStreamChat(msg, memoryId);

        return Flux.create(fluxSink -> {
            tokenStream
                    .onNext(fluxSink::next)
                    .onComplete(response -> fluxSink.complete())
                    .onError(fluxSink::error)
                    .start();

            fluxSink.onDispose(() -> {
                // 客户端断开时的清理工作（如有需要可在此扩展）
            });
        });
    }

    /**
     * 一次性返回完整的思考过程列表（非流式，便于调试）。
     *
     * @param msg      用户输入
     * @param memoryId 会话 ID
     * @return 思考过程列表
     */
    @GetMapping("/thoughts")
    public List<ThoughtItem> thoughts(@RequestParam("msg") String msg,
                                      @RequestParam(value = "memoryId", required = false) String memoryId) throws Exception {
        return graphAgent.streamChat(msg, memoryId);
    }
}
