package org.dromara.web.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 首页
 *
 * @author Lion Li
 */
@SaIgnore
@Controller
public class IndexController {

    /**
     * 访问首页，转发到前端 index.html
     */
    @GetMapping("/")
    public String index() {
        return "forward:/index.html";
    }

}
