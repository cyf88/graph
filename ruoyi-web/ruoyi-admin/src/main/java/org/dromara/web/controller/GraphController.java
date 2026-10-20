package org.dromara.web.controller;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.web.domain.vo.GraphResult;
import org.dromara.web.domain.vo.GraphStats;
import org.dromara.web.service.GraphService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 户籍关系图谱
 *
 * @author hukou
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/graph")
public class GraphController {

    private final GraphService graphService;

    /**
     * 查询直系亲属
     *
     * @param gmsfhm 身份证号
     * @param level  扩展级数（0 默认，1..8）
     * @return 图谱结果
     */
    @GetMapping("/person/direct")
    public R<GraphResult> directRelatives(@RequestParam String gmsfhm, @RequestParam(defaultValue = "0") int level) {
        return R.ok(graphService.getDirectRelatives(gmsfhm, level));
    }

    /**
     * 查询两人关系路径
     *
     * @param from  身份证号 A
     * @param to    身份证号 B
     * @param level 最大级数（0 默认，1..8）
     * @return 图谱结果
     */
    @GetMapping("/relation/path")
    public R<GraphResult> relationPath(@RequestParam String from, @RequestParam String to, @RequestParam(defaultValue = "0") int level) {
        return R.ok(graphService.getRelationPath(from, to, level));
    }

    /**
     * 图谱统计（首页数据总览）
     *
     * @return 统计结果
     */
    @GetMapping("/stats")
    public R<GraphStats> stats() {
        return R.ok(graphService.getStats());
    }
}
