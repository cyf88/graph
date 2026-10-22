package org.dromara.web.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * AI MCP 服务器配置对象 ai_mcp_config
 *
 * @author hukou
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_mcp_config")
public class AiMcpConfig extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 服务器名称
     */
    private String name;

    /**
     * 传输类型：streamable-http / sse
     */
    private String transport;

    /**
     * 服务器地址
     */
    private String url;

    /**
     * 是否启用（0停用 1启用）
     */
    private Integer enabled;

    /**
     * 备注
     */
    private String remark;
}
