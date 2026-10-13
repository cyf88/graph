package com.hukou.web.dto;

import java.util.List;

public record GraphResult(boolean hasRelation, List<GraphNode> nodes, List<GraphEdge> edges, String description) {
}
