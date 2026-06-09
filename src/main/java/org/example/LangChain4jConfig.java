package org.example;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class LangChain4jConfig {

    /**
     * 1. 手动创建大模型底层驱动 Bean
     */
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .baseUrl("http://localhost:11434/v1") // 连本地 Ollama 必须加 /v1
                .apiKey("not-needed-for-ollama")     // Ollama 不需要，但底层校验非空，随便填
                .modelName("gemma2:2b")                 // 换成你本地实际 pull 的模型名（如 qwen2）
                .temperature(0.7)
                .timeout(Duration.ofSeconds(60))     // 本地推理慢，建议把超时时间调大到 60 秒
                .logRequests(true)                   // 调试利器：控制台打印请求日志
                .logResponses(true)                  // 调试利器：控制台打印响应日志
                .build();
    }

    /**
     * 1. 手动创建大模型底层驱动 Bean
     */
    @Bean
    public StreamingChatLanguageModel streamingchatLanguageModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl("http://localhost:11434/v1") // 连本地 Ollama 必须加 /v1
                .apiKey("not-needed-for-ollama")     // Ollama 不需要，但底层校验非空，随便填
                .modelName("gemma2:2b")                 // 换成你本地实际 pull 的模型名（如 qwen2）
                .temperature(0.7)
                .timeout(Duration.ofSeconds(60))     // 本地推理慢，建议把超时时间调大到 60 秒
                .logRequests(true)                   // 调试利器：控制台打印请求日志
                .logResponses(true)                  // 调试利器：控制台打印响应日志
                .build();
    }

    /**
     * 2. 手动创建你的智能体（AiService） Bean
     * 这样你在业务代码里就能直接 @Autowired QAQueryAgent 了
     */
    @Bean
    public QAQueryAgent qaQueryAgent(ChatLanguageModel chatLanguageModel, StreamingChatLanguageModel streamingChatLanguageModel) {
        return AiServices.builder(QAQueryAgent.class)
                .chatLanguageModel(chatLanguageModel)
                .streamingChatLanguageModel(streamingChatLanguageModel)
                // 后续如果你加了别的组的知识库工具，或者你自己的向量库，直接在下面 .tools() 即可
                // .contentRetriever(yourContentRetriever)
                .build();
    }


}
