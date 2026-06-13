package org.example;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class AgentTool {

    @Tool("总结问答内容,本工具不需要传参")
    public String testTool () {

        return "测试工具调用";
    }
}
