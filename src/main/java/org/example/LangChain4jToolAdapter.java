package org.example;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.service.tool.DefaultToolExecutor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 自实现的工具节点，用于绕开 langgraph4j-langchain4j 1.0-rc2
 * 与 langchain4j 0.32.0 之间关于 DefaultToolExecutor 包路径不兼容的问题。
 *
 * <p>功能等价于官方的 {@code org.bsc.langgraph4j.langchain4j.tool.ToolNode}：
 * <ul>
 *   <li>扫描带 {@link Tool} 注解的方法生成 {@link ToolSpecification}</li>
 *   <li>根据 LLM 返回的 {@link ToolExecutionRequest} 反射执行对应工具方法</li>
 * </ul>
 */
public final class LangChain4jToolAdapter {

    private final List<ToolSpecification> specifications;
    private final List<Object> toolTargets;
    private final List<DefaultToolExecutor> executors;

    private LangChain4jToolAdapter(List<ToolSpecification> specifications,
                                   List<Object> toolTargets,
                                   List<DefaultToolExecutor> executors) {
        this.specifications = Collections.unmodifiableList(specifications);
        this.toolTargets = Collections.unmodifiableList(toolTargets);
        this.executors = Collections.unmodifiableList(executors);
    }

    public static LangChain4jToolAdapter of(Object... tools) {
        return of(Arrays.asList(tools));
    }

    public static LangChain4jToolAdapter of(Collection<Object> tools) {
        List<ToolSpecification> specs = new ArrayList<>();
        List<Object> targets = new ArrayList<>();
        List<DefaultToolExecutor> executors = new ArrayList<>();

        for (Object tool : tools) {
            Class<?> cls = tool.getClass();
            for (Method method : cls.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    specs.add(ToolSpecifications.toolSpecificationFrom(method));
                    targets.add(tool);
                    executors.add(new DefaultToolExecutor(tool, method));
                }
            }
        }

        if (specs.isEmpty()) {
            throw new IllegalArgumentException("No @Tool annotated methods found in provided tools");
        }

        return new LangChain4jToolAdapter(specs, targets, executors);
    }

    /** 返回所有已注册的工具规格，用于传给 LLM */
    public List<ToolSpecification> toolSpecifications() {
        return specifications;
    }

    /** 执行单个工具调用请求 */
    public Optional<ToolExecutionResultMessage> execute(ToolExecutionRequest request) {
        if (request == null) {
            return Optional.empty();
        }
        String toolName = request.name();
        for (int i = 0; i < specifications.size(); i++) {
            if (specifications.get(i).name().equals(toolName)) {
                String result = executors.get(i).execute(request, toolTargets.get(i));
                return Optional.of(ToolExecutionResultMessage.from(request, result));
            }
        }
        return Optional.empty();
    }

    /** 批量执行工具调用请求，合并结果 */
    public Optional<ToolExecutionResultMessage> execute(Collection<ToolExecutionRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder();
        boolean anyExecuted = false;
        ToolExecutionRequest firstRequest = null;
        for (ToolExecutionRequest req : requests) {
            Optional<ToolExecutionResultMessage> r = execute(req);
            if (r.isPresent()) {
                anyExecuted = true;
                if (firstRequest == null) {
                    firstRequest = req;
                }
                sb.append(r.get().text()).append("\n");
            }
        }
        if (!anyExecuted || firstRequest == null) {
            return Optional.empty();
        }
        return Optional.of(ToolExecutionResultMessage.toolExecutionResultMessage(
                firstRequest, sb.toString()));
    }
}
