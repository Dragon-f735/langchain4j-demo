package org.example;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.omg.CORBA.PRIVATE_MEMBER;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RedisCharMemoryStore implements ChatMemoryStore {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ChatMessage> getMessages(Object o) {
        String json = stringRedisTemplate.opsForValue().get(o);

        return ChatMessageDeserializer.messagesFromJson(json);
    }

    @Override
    public void updateMessages(Object o, List<ChatMessage> list) {

        String json = ChatMessageSerializer.messagesToJson(list);

        stringRedisTemplate.opsForValue().set(o.toString(), json);
    }

    @Override
    public void deleteMessages(Object o) {
        stringRedisTemplate.delete(o.toString());
    }
}
