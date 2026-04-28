package com.opencode.cui.skill.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.StreamMessage.QuestionInfo;
import com.opencode.cui.skill.model.StreamMessage.QuestionItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StreamMessage 模型单元测试：验证嵌套类的字段定义与 JSON 序列化。
 */
class StreamMessageTest {

    @Test
    void questionInfo_serializesQuestionsListAndExtParam() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode extParam = mapper.readTree("{\"key\":\"value\"}");
        QuestionInfo info = QuestionInfo.builder()
                .header("h").question("q1").options(List.of("A", "B"))
                .questions(List.of(
                        QuestionItem.builder().header("h").question("q1").options(List.of("A", "B")).build(),
                        QuestionItem.builder().question("q2").options(List.of("C")).build()))
                .extParam(extParam)
                .build();
        String json = mapper.writeValueAsString(info);
        assertThat(json).contains("\"questions\":[");
        assertThat(json).contains("\"q2\"");
        assertThat(json).contains("\"extParam\":{\"key\":\"value\"}");
    }
}
