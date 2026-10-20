package org.dromara.web.domain.vo;

import java.util.List;

/**
 * 图谱统计数据
 *
 * @author hukou
 */
public record GraphStats(long nodeCount, long edgeCount, List<RelationTypeCount> relationTypes, List<GenderCount> genderStats) {

    /**
     * 关系类型统计
     */
    public record RelationTypeCount(String type, String label, long count) {
    }

    /**
     * 性别统计
     */
    public record GenderCount(String code, String label, long count) {
    }
}
