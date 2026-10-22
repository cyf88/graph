package org.dromara.web.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * AI 对话请求
 *
 * @author hukou
 */
@Data
public class AiChatRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 大模型配置 ID，为空时使用默认配置
     */
    private Long llmId;

    /**
     * 对话消息列表
     */
    private List<Message> messages;

    @Data
    public static class Message implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 角色：system / user / assistant
         */
        private String role;

        /**
         * 消息内容
         */
        private String content;
    }
}
