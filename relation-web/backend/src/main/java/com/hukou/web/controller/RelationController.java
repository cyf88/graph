package com.hukou.web.controller;

import com.hukou.web.dto.GraphResult;
import com.hukou.web.service.GraphService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class RelationController {

    private final GraphService graphService;

    public RelationController(GraphService graphService) {
        this.graphService = graphService;
    }

    /** 需求一：输入一个身份证号，查询其直系亲属 */
    @GetMapping("/person/direct")
    public GraphResult directRelatives(@RequestParam String gmsfhm,
                                       @RequestParam(defaultValue = "0") int level) {
        return graphService.getDirectRelatives(gmsfhm.trim(), level);
    }

    /** 需求二：输入两个身份证号，查询两人是否存在关系 */
    @GetMapping("/relation/path")
    public GraphResult relationPath(@RequestParam String from, @RequestParam String to,
                                    @RequestParam(defaultValue = "0") int level) {
        return graphService.getRelationPath(from.trim(), to.trim(), level);
    }
}
