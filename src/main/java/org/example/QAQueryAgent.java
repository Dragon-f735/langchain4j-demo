package org.example;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;


public interface QAQueryAgent {

    @SystemMessage({
            "你是一个资深的 Java 后端专家和架构师。",
            "请用专业、严谨且通俗易懂的语言来回答研发人员提出的技术问题。",
            "必须用中文回复",
            "如果用户想要总结问答内容，使用相关工具"
    })
    //@SystemMessage(fromResource = "system.txt")
    TokenStream chat(@UserMessage String userMessage, @MemoryId String memoryId); // 声明式接口，方法名叫 chat 或 ask 都可以
}
