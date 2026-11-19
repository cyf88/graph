package com.hukou.marriage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 程序配置：支持从配置文件读取 MySQL / Neo4j 连接信息及婚姻状态映射。
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
    public final Set<String> spouseStatuses;
    public final Set<String> divorceStatuses;
    public final String spouseRelType;
    public final String divorceRelType;
    public final int batchSize;
    public final boolean createMissingNodes;

    private Config(Properties props) {
        this.mysqlUrl = props.getProperty("mysql.url");
        this.mysqlUser = props.getProperty("mysql.user");
        this.mysqlPassword = props.getProperty("mysql.password");
        this.table = props.getProperty("mysql.table");
        this.neo4jUri = props.getProperty("neo4j.uri");
        this.neo4jUser = props.getProperty("neo4j.user");
        this.neo4jPassword = props.getProperty("neo4j.password");
        this.queryTimeoutSeconds = parseLong(props.getProperty("mysql.query_timeout_seconds"), 86400L);
        this.spouseStatuses = parseCsv(props.getProperty("hydj.spouse_statuses"), "IA");
        this.divorceStatuses = parseCsv(props.getProperty("hydj.divorce_statuses"), "IB");
        this.spouseRelType = parseRelType(props.getProperty("relation.spouse"), "MARRIED_TO");
        this.divorceRelType = parseRelType(props.getProperty("relation.divorce"), "DIVORCED_FROM");
        this.batchSize = (int) Math.min(Math.max(parseLong(props.getProperty("write.batch_size"), 10000L), 1L), 50000L);
        this.createMissingNodes = parseBoolean(props.getProperty("graph.create_missing_nodes"), true);
    }

    public static Config load(String[] args) throws IOException {
        Properties defaults = new Properties();
        defaults.setProperty("mysql.url", "jdbc:mysql://172.17.43.25:3308/hukou"
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8");
        defaults.setProperty("mysql.user", "root");
        defaults.setProperty("mysql.password", "root");
        defaults.setProperty("mysql.table", "zl_szf_sga_zxk_smzj_hy_hydjxx");
        defaults.setProperty("neo4j.uri", "bolt://172.17.43.137:7687");
        defaults.setProperty("neo4j.user", "neo4j");
        defaults.setProperty("neo4j.password", "neo4j123");
        defaults.setProperty("mysql.query_timeout_seconds", "86400");

        defaults.setProperty("hydj.spouse_statuses", "IA");
        defaults.setProperty("hydj.divorce_statuses", "IB");
        defaults.setProperty("relation.spouse", "MARRIED_TO");
        defaults.setProperty("relation.divorce", "DIVORCED_FROM");
        defaults.setProperty("write.batch_size", "10000");
        defaults.setProperty("graph.create_missing_nodes", "true");

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

    private static Set<String> parseCsv(String value, String fallback) {
        String v = (value == null || value.trim().isEmpty()) ? fallback : value;
        LinkedHashSet<String> set = Arrays.stream(v.split(","))
                .map(String::trim)
                .map(s -> s.toUpperCase(java.util.Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (set.isEmpty()) {
            throw new IllegalArgumentException("婚姻状态列表不能为空");
        }
        return set;
    }

    /** 关系类型会内插到 Cypher，限制为合法标识符。 */
    private static String parseRelType(String value, String fallback) {
        String v = (value == null || value.trim().isEmpty()) ? fallback : value.trim();
        if (!v.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("非法关系类型名: " + v);
        }
        return v;
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

    private static boolean parseBoolean(String s, boolean def) {
        if (s == null || s.trim().isEmpty()) {
            return def;
        }
        return Boolean.parseBoolean(s.trim());
    }
}
