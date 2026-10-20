package org.dromara.web.domain.vo;

import java.util.List;

/**
 * 图谱查询结果
 *
 * @author hukou
 */
public record GraphResult(boolean hasRelation, List<GraphNode> nodes, List<GraphEdge> edges, String description) {
}
