package org.dromara.web.domain.vo;

/**
 * 图谱节点
 *
 * @author hukou
 */
public record GraphNode(String id, String name, String gmsfhm, String xbdm, String yhzgxdm, boolean center, boolean endpoint) {
}
