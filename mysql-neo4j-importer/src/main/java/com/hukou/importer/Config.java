package com.hukou.importer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 程序配置：支持从配置文件读取 MySQL / Neo4j 连接信息。
 *
 * 读取优先级：
 *   1. 命令行参数 --config=路径 指定的文件
 *   2. 运行目录下的 config.properties
 *   3. classpath 内置的 config.properties（模板）
 * 未配置的项回退到代码中的默认值。
 */
public class Config {

    public final String mysqlUrl;
    public final String mysqlUser;
    public final String mysqlPassword;
    public final String table;
    public final String neo4jUri;
    public final String neo4jUser;
    public final String neo4jPassword;
    public final long queryTimeoutSeconds;

    private Config(Properties props) {
        this.mysqlUrl = props.getProperty("mysql.url");
        this.mysqlUser = props.getProperty("mysql.user");
        this.mysqlPassword = props.getProperty("mysql.password");
        this.table = props.getProperty("mysql.table");
        this.neo4jUri = props.getProperty("neo4j.uri");
        this.neo4jUser = props.getProperty("neo4j.user");
        this.neo4jPassword = props.getProperty("neo4j.password");
        this.queryTimeoutSeconds = parseLong(props.getProperty("mysql.query_timeout_seconds"), 86400L);
    }

    public static Config load(String[] args) throws IOException {
        Properties defaults = new Properties();
        defaults.setProperty("mysql.url", "jdbc:mysql://172.17.43.25:3308/hukou"
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8");
        defaults.setProperty("mysql.user", "root");
        defaults.setProperty("mysql.password", "root");
        defaults.setProperty("mysql.table", "zl_rk_xzrk_pb_hj_jbxx");
        defaults.setProperty("neo4j.uri", "bolt://172.17.43.137:7687");
        defaults.setProperty("neo4j.user", "neo4j");
        defaults.setProperty("neo4j.password", "neo4j123");
        defaults.setProperty("mysql.query_timeout_seconds", "86400");

        Properties props = new Properties(defaults);

        // 命令行 --config=路径
        String configPath = null;
        for (String arg : args) {
            if (arg != null && arg.startsWith("--config=")) {
                configPath = arg.substring("--config=".length()).trim();
            }
        }

        // 外部配置文件优先
        Path file = (configPath != null && !configPath.isEmpty())
                ? Path.of(configPath)
                : Path.of("config.properties");
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            }
            System.out.println("已加载配置文件: " + file.toAbsolutePath());
            return new Config(props);
        }

        // 回退到 classpath 内置模板
        try (InputStream in = Config.class.getResourceAsStream("/config.properties")) {
            if (in != null) {
                props.load(in);
                System.out.println("已加载内置配置 config.properties");
            }
        }
        return new Config(props);
    }

    private static long parseLong(String s, long def) {
        if (s == null || s.trim().isEmpty()) {
            return def;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
