# LangGraph4j 升级 JDK 17 迁移指南

本文档记录了如何将当前基于 JDK 8 的 `langgraph4j` 集成方案，平滑迁移到 JDK 17 + `langgraph4j 1.5.12` 官方版本。

---

## 1. 当前状态 (JDK 8 + langgraph4j 1.0-rc2)

### 1.1 约束
- 项目使用 JDK 8（字节码 major version = 52）
- `langgraph4j-core-jdk8 1.0-rc2` 是最后一个支持 JDK 8 的版本
- **没有** `LC4jStateSerializer`、`MessagesState`、`MessagesStateGraph` 等官方预构建类

### 1.2 手写的兼容代码（共 3 个文件）

| 文件 | 作用 |
|---|---|
| `InMemoryStateSerializer.java` | 手写序列化器，绕过 `ChatMessage` 不可序列化的问题 |
| `MessageState.java` | 自定义消息状态封装 |
| `MessagesStateGraph.java` | 自定义消息状态图封装 |

### 1.3 pom.xml 关键配置
```xml
<properties>
    <maven.compiler.source>8</maven.compiler.source>
    <maven.compiler.target>8</maven.compiler.target>
</properties>

<dependency>
    <groupId>org.bsc.langgraph4j</groupId>
    <artifactId>langgraph4j-core-jdk8</artifactId>
    <version>1.0-rc2</version>
</dependency>
```

---

## 2. 迁移步骤 (升级到 JDK 17 + langgraph4j 1.5.12)

### 2.1 修改 pom.xml

```xml
<properties>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
</properties>

<!-- 替换 jdk8 版本为标准版本 -->
<dependency>
    <groupId>org.bsc.langgraph4j</groupId>
    <artifactId>langgraph4j-core</artifactId>
    <version>1.5.12</version>
</dependency>
<dependency>
    <groupId>org.bsc.langgraph4j</groupId>
    <artifactId>langgraph4j-langchain4j</artifactId>
    <version>1.5.12</version>
</dependency>
```

### 2.2 删除手写的兼容文件

删除以下 3 个文件（不再需要）：
- ❌ `src/main/java/org/example/InMemoryStateSerializer.java`
- ❌ `src/main/java/org/example/MessageState.java`
- ❌ `src/main/java/org/example/MessagesStateGraph.java`

### 2.3 修改 LangGraphInterGateLangChain4j.java

**Before (JDK 8 版本)：**
```java
import org.example.InMemoryStateSerializer;
import org.example.MessageState;

// 构造器里：
StateSerializer<MessageState<ChatMessage>> stateSerializer =
        new InMemoryStateSerializer<>(MessageState::new);

StateGraph<MessageState<ChatMessage>> g = new MessagesStateGraph<>(stateSerializer);
```

**After (JDK 17 版本)：**
```java
import org.bsc.langgraph4j.langchain4j.serializer.std.LC4jStateSerializer;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.prebuilt.MessagesStateGraph;

// 构造器里：
LC4jStateSerializer<MessagesState<ChatMessage>> stateSerializer =
        new LC4jStateSerializer<>(MessagesState::new);

StateGraph<MessagesState<ChatMessage>> g = new MessagesStateGraph<>(stateSerializer);
```

### 2.4 其他小改动

| 改动点 | 说明 |
|---|---|
| 泛型类型 | `MessageState<ChatMessage>` → `MessagesState<ChatMessage>` |
| `MessageState.MESSAGES_KEY` | 改用官方常量或字符串 `"messages"` |
| `state.messages()` | 保持不变，官方 `MessagesState` 也提供该方法 |
| `state.lastMessage()` | 保持不变 |

---

## 3. 升级后的好处

| 项目 | JDK 8 方案 | JDK 17 + 1.5.12 方案 |
|---|---|---|
| 序列化 | 手写 `InMemoryStateSerializer` | ✅ 官方 `LC4jStateSerializer` (基于 Jackson) |
| 状态类 | 手写 `MessageState` | ✅ 官方 `MessagesState` |
| 图类 | 手写 `MessagesStateGraph` | ✅ 官方 `MessagesStateGraph` |
| 代码量 | 多 3 个文件 ~200 行 | 零额外代码 |
| 维护成本 | 需跟随 langchain4j API 变化 | 官方维护 |
| 未来兼容性 | 1.0-rc2 是 jdk8 分支，不会再有新特性 | 跟随主版本更新 |

---

## 4. 验证清单

升级后执行以下检查：

- [ ] `mvn clean compile` 编译通过
- [ ] `mvn spring-boot:run` 启动正常
- [ ] 调用 `/graph/ask?msg=你好` 返回正确结果
- [ ] 多轮对话（带 `memoryId`）正常
- [ ] 工具调用（`AgentTool` 的 `@Tool` 方法）正常触发
- [ ] RAG 检索（Chroma 向量库）正常返回参考资料
- [ ] 日志里无 `NotSerializableException` 或 `UnsupportedClassVersionError`

---

## 5. 回滚方案（如需）

如果升级后出问题，可以快速回滚：

1. 把 `pom.xml` 恢复为：
   ```xml
   <maven.compiler.source>8</maven.compiler.source>
   <maven.compiler.target>8</maven.compiler.target>
   ```
2. 恢复 `langgraph4j-core-jdk8 1.0-rc2` 依赖
3. 恢复 `InMemoryStateSerializer.java`、`MessageState.java`、`MessagesStateGraph.java`
4. 重新编译启动

---

## 附录：版本对应关系

| langgraph4j 版本 | JDK 要求 | 核心特性 |
|---|---|---|
| 1.0-rc2 (jdk8) | JDK 8 | 基础 StateGraph，无官方 LC4j 适配 |
| 1.5.12 | JDK 17 | ✅ `LC4jStateSerializer`、`MessagesState`、`MessagesStateGraph` |
| 1.8-SNAPSHOT | JDK 17 | 最新预览版，额外增加 AgentExecutor 等 |

> **注意**：`langgraph4j-core-jdk8` 是 1.0-rc2 的特例，后续版本不再发布 jdk8 变体。
