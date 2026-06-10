package org.example;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/ai")
public class AiController {

    @Autowired
    private QAQueryAgent qaQueryAgent; // 直接注入你刚刚手动声明的 Bean

    @GetMapping(value = "/ask", produces = "text/html;charset=utf-8")
    public Flux<String> ask(@RequestParam("msg") String msg, @RequestParam("memoryId") String memoryId) {
        // 直接调用，动态代理会自动去调 Ollama 的 generate
        // 调用智能体拿到 TokenStream
        TokenStream tokenStream = qaQueryAgent.chat(msg, memoryId);

        // 💡 重点 2：利用 Flux.create，把 TokenStream 的回调揉进 WebFlux 的响应式管道里
        return Flux.create(fluxSink -> {
            // 大模型每吐一个字，就通过向下游管道发射一个信号
            // 发生异常，向下游传递错误
            tokenStream
                    .onNext(fluxSink::next)
                    .onComplete(response -> {
                        // 吐完了，通知 WebFlux 管道关闭
                        fluxSink.complete();
                    })
                    .onError(fluxSink::error)
                    .start(); // 👈 启动 LangChain4j 的流

            // 可选：当客户端连接断开时，可以做一些清理工作
            fluxSink.onDispose(() -> {
                // 如果需要中途取消大模型请求，可以在这里扩展
            });
        });
    }
}
