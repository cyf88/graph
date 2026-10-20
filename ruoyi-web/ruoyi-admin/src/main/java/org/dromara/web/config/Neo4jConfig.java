package org.dromara.web.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Neo4j 图数据库连接配置
 *
 * @author hukou
 */
@Configuration
public class Neo4jConfig {

    @Value("${neo4j.uri}")
    private String uri;

    @Value("${neo4j.username}")
    private String username;

    @Value("${neo4j.password}")
    private String password;

    @Bean(destroyMethod = "close")
    public Driver neo4jDriver() {
        return GraphDatabase.driver(uri, AuthTokens.basic(username, password));
    }
}
