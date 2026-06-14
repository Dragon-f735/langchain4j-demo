package org.example;

import com.sun.org.apache.regexp.internal.RE;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentLoader;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.source.FileSystemSource;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.xml.DefaultDocumentLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Paths;
import java.time.Duration;

import static java.nio.charset.StandardCharsets.UTF_8;

@Configuration
public class LangChain4jConfig {

    @Autowired
    private RedisCharMemoryStore redisCharMemoryStore;

    @Autowired
    private AgentTool agentTool;

    /**
     * 1. 手动创建大模型底层驱动 Bean
     */
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .baseUrl("http://localhost:11434/v1") // 连本地 Ollama 必须加 /v1
                .apiKey("not-needed-for-ollama")     // Ollama 不需要，但底层校验非空，随便填
                .modelName("qwen3.5:0.8b")                 // 换成你本地实际 pull 的模型名（如 qwen2）
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
                .modelName("qwen3.5:0.8b")                 // 换成你本地实际 pull 的模型名（如 qwen2）
                .temperature(0.7)
                .timeout(Duration.ofSeconds(60))     // 本地推理慢，建议把超时时间调大到 60 秒
                .logRequests(true)                   // 调试利器：控制台打印请求日志
                .logResponses(true)                  // 调试利器：控制台打印响应日志
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel () {
        return OpenAiEmbeddingModel.builder()
                .baseUrl("http://localhost:11434/v1")
                .apiKey("not-needed-for-ollama")
                .modelName("nomic-embed-text:v1.5")
                .timeout(Duration.ofSeconds(60))
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    @Bean
    public EmbeddingStore embeddingStore (EmbeddingModel embeddingModel) {

        return ChromaEmbeddingStore.builder()
                .baseUrl("http://127.0.0.1:8000")
                .collectionName("langchain4j-chroma-vector")
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    /**
     * 2. 手动创建你的智能体（AiService） Bean
     * 这样你在业务代码里就能直接 @Autowired QAQueryAgent 了
     */
    @Bean
    public QAQueryAgent qaQueryAgent(ChatLanguageModel chatLanguageModel,
                                     StreamingChatLanguageModel streamingChatLanguageModel,
                                     ChatMemory chatMemory,
                                     ChatMemoryProvider chatMemoryProvider,
                                     ContentRetriever contentRetriever) {
        return AiServices.builder(QAQueryAgent.class)
                .chatLanguageModel(chatLanguageModel)
                .streamingChatLanguageModel(streamingChatLanguageModel)
                //.chatMemory(chatMemory)
                .chatMemoryProvider(chatMemoryProvider)
                .contentRetriever(contentRetriever)
                .tools(agentTool)
                // 后续如果你加了别的组的知识库工具，或者你自己的向量库，直接在下面 .tools() 即可
                // .contentRetriever(yourContentRetriever)
                .build();
    }

    @Bean
    public ChatMemory chatMemory() {

        return MessageWindowChatMemory.builder()
                .maxMessages(20)
                .build();
    }

    @Bean
    public ChatMemoryProvider chatMemoryProvider() {

        return new ChatMemoryProvider() {
            @Override
            public ChatMemory get(Object o) {
                return MessageWindowChatMemory.builder()
                        .id(o)
                        .maxMessages(20)
                        .chatMemoryStore(redisCharMemoryStore)
                        .build();
            }
        };
    }

    //@Bean
    public EmbeddingStore store(EmbeddingModel embeddingModel,
                                EmbeddingStore embeddingStore) throws IOException {

        Document document = FileSystemDocumentLoader.loadDocument(Paths.get("E:\\AI_PROJECT\\langchain4j-demo\\src\\main\\resources\\Git教程.md"), new ApacheTikaDocumentParser());

        //InMemoryEmbeddingStore inMemoryEmbeddingStore = new InMemoryEmbeddingStore<>();

        DocumentSplitter splitter = DocumentSplitters.recursive(500, 100);

        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .embeddingStore(embeddingStore)
                .documentSplitter(splitter)
                .embeddingModel(embeddingModel)
                .build();
        ingestor.ingest(document);
        return embeddingStore;
    }

    @Bean
    public ContentRetriever contentRetriever(EmbeddingStore embeddingStore,
                                             EmbeddingModel embeddingModel) {

       return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .minScore(0.5)
                .maxResults(3)
                .build();
    }

}
