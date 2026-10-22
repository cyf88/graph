-- AI 助手初始化脚本
-- 目标库：ry-vue（与若依系统库一致）
-- 执行前请确认已选中 ry-vue 库

-- 1. AI 大模型配置表
CREATE TABLE IF NOT EXISTS `ai_llm_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(50) NOT NULL COMMENT '配置名称',
  `base_url` varchar(255) NOT NULL COMMENT 'API 地址（OpenAI 兼容）',
  `api_key` varchar(255) NOT NULL COMMENT 'API 密钥',
  `model` varchar(100) NOT NULL COMMENT '模型名称',
  `temperature` double DEFAULT '0.7' COMMENT '采样温度',
  `max_tokens` int DEFAULT '2048' COMMENT '最大输出 token',
  `is_default` tinyint DEFAULT '0' COMMENT '是否默认（0否 1是）',
  `enabled` tinyint DEFAULT '1' COMMENT '是否启用（0停用 1启用）',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT '' COMMENT '备注',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI 大模型配置表';

-- 2. AI MCP 服务器配置表
CREATE TABLE IF NOT EXISTS `ai_mcp_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(50) NOT NULL COMMENT '服务器名称',
  `transport` varchar(30) NOT NULL DEFAULT 'streamable-http' COMMENT '传输类型：streamable-http/sse',
  `url` varchar(255) DEFAULT NULL COMMENT '服务器地址（streamable-http/sse）',
  `enabled` tinyint DEFAULT '1' COMMENT '是否启用（0停用 1启用）',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT '' COMMENT '备注',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI MCP 服务器配置表';

-- 3. AI 助手菜单（一级菜单，位于系统管理之后）
INSERT INTO `sys_menu` (`menu_id`,`menu_name`,`parent_id`,`order_num`,`path`,`component`,`query_param`,`is_frame`,`is_cache`,`menu_type`,`visible`,`status`,`perms`,`icon`,`create_dept`,`create_by`,`create_time`,`update_by`,`update_time`,`remark`) VALUES
(1626,'AI助手',0,4,'ai','ai/index','',1,0,'C','0','0','','ai',103,1,NOW(),NULL,NULL,'AI助手');
