package org.dromara.web.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * AI 大模型配置对象 ai_llm_config
 *
 * @author hukou
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_llm_config")
public class AiLlmConfig extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 配置名称
     */
    private String name;

    /**
     * API 地址（OpenAI 兼容）
     */
    private String baseUrl;

    /**
     * API 密钥
     */
    private String apiKey;

    /**
     * 模型名称
     */
    private String model;

    /**
     * 采样温度
     */
    private Double temperature;

    /**
     * 最大输出 token
     */
    private Integer maxTokens;

    /**
     * 是否默认（0否 1是）
     */
    private Integer isDefault;

    /**
     * 是否启用（0停用 1启用）
     */
    private Integer enabled;

    /**
     * 备注
     */
    private String remark;
}
