package com.hukou.marriage;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 读取婚姻登记信息表（zl_szf_sga_zxk_smzj_hy_hydjxx），补充 Neo4j 中的配偶/离异关系。
 *
 * 数据映射（婚姻状态 hyzt）：
 *   IA 已婚        -> MARRIED_TO      （夫妻关系，无向，按身份证号字典序规范化方向）
 *   IB 离异        -> DIVORCED_FROM   （离异关系，无向，按身份证号字典序规范化方向）
 *   ICA 结婚待领证 -> 默认不处理（可通过 hydj.spouse_statuses 配置纳入）
 *   ICB 离婚待领证 -> 默认不处理（可通过 hydj.divorce_statuses 配置纳入）
 *
 * 处理逻辑：
 *   1. 仅查询配置中指定的婚姻状态，JDBC 流式读取，避免一次性加载全部数据；
 *   2. 每行取男/女身份证号作为一条关系的两端，跳过证件号为空或相同的记录；
 *   3. 按配置批量写入 Neo4j，边方向按身份证号字典序规范化，保证同一对人员只建一条边；
 *   4. 只建立关系，不写入任何节点/关系属性。
 *
 * 该程序只做关系补充，不删除图谱中已有的配偶边；因此历史婚姻与离异关系会同时保留。
 */
public class MarriageRelSupplement {

    private static final long LOG_EVERY = 500000;

    /** 需要读取的源表字段 */
    private static final String COLUMNS = "sfzjh_n, sfzjh_nv, hyzt";

    public static void main(String[] args) throws Exception {
        Config config = Config.load(args);
        long start = System.currentTimeMillis();
        supplement(config);
        System.out.printf("全部完成，总耗时 %.1f 分钟%n", (System.currentTimeMillis() - start) / 60000.0);
    }

    private static void supplement(Config config) throws Exception {
        Set<String> allStatuses = new LinkedHashSet<>(config.spouseStatuses);
        allStatuses.addAll(config.divorceStatuses);
        String sql = "SELECT " + COLUMNS + " FROM " + config.table
                + " WHERE UPPER(hyzt) IN (" + quoteAll(allStatuses) + ")";

        try (Driver driver = GraphDatabase.driver(config.neo4jUri, AuthTokens.basic(config.neo4jUser, config.neo4jPassword));
             Session session = driver.session();
             Connection conn = DriverManager.getConnection(config.mysqlUrl, config.mysqlUser, config.mysqlPassword);
             Statement stmt = conn.createStatement()) {

            System.out.printf("源表：%s，批大小：%d，Neo4j：%s%n", config.table, config.batchSize, config.neo4jUri);
            System.out.printf("婚姻状态映射：%s -> %s，%s -> %s%n",
                    config.spouseStatuses, config.spouseRelType,
                    config.divorceStatuses, config.divorceRelType);
            System.out.printf("端点人员缺失时%s%n", config.createMissingNodes ? "创建最小 Person 节点" : "跳过（仅连接已存在人员）");

            session.run("CREATE CONSTRAINT person_gmsfhm_unique IF NOT EXISTS "
                    + "FOR (p:Person) REQUIRE p.gmsfhm IS UNIQUE").consume();

            if (config.queryTimeoutSeconds > 0) {
                try {
                    stmt.execute("SET query_timeout = " + config.queryTimeoutSeconds);
                    System.out.println("已设置源库查询超时：" + config.queryTimeoutSeconds + " 秒");
                } catch (SQLException e) {
                    System.out.println("设置源库查询超时失败（非 StarRocks 可忽略）：" + e.getMessage());
                }
            }

            stmt.setFetchSize(Integer.MIN_VALUE);
            System.out.println("开始流式读取并分批写入...");

            long candidates = 0, created = 0, skipped = 0;
            Map<String, Long> createdByRel = new LinkedHashMap<>();
            List<Map<String, Object>> batch = new ArrayList<>(config.batchSize);

            try (ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    String hyzt = trimToNull(rs.getString("hyzt"));
                    String relType = classify(hyzt, config);
                    if (relType == null) {
                        continue;
                    }
                    String maleId = trimToNull(rs.getString("sfzjh_n"));
                    String femaleId = trimToNull(rs.getString("sfzjh_nv"));
                    if (maleId == null || femaleId == null || maleId.equals(femaleId)) {
                        skipped++;
                        continue;
                    }

                    boolean maleFirst = maleId.compareTo(femaleId) <= 0;
                    Map<String, Object> row = new HashMap<>();
                    row.put("a", maleFirst ? maleId : femaleId);
                    row.put("b", maleFirst ? femaleId : maleId);
                    row.put("relType", relType);

                    batch.add(row);
                    candidates++;
                    if (batch.size() >= config.batchSize) {
                        created += writeBatch(session, batch, config, createdByRel);
                        batch.clear();
                        logProgress(candidates);
                    }
                }
                if (!batch.isEmpty()) {
                    created += writeBatch(session, batch, config, createdByRel);
                }
            }

            System.out.printf("候选婚姻登记记录：%d，实际新增关系：%d，已存在或端点缺失跳过：%d，"
                            + "证件号为空/相同跳过：%d%n",
                    candidates, created, candidates - created, skipped);
            for (Map.Entry<String, Long> e : createdByRel.entrySet()) {
                System.out.printf("本次新增 %s = %d%n", e.getKey(), e.getValue());
            }
            printSummary(session, config);
        }
    }

    /** 根据婚姻状态返回关系类型；不在配置范围内返回 null。 */
    private static String classify(String hyzt, Config config) {
        if (hyzt == null) {
            return null;
        }
        String code = hyzt.trim().toUpperCase(Locale.ROOT);
        if (config.spouseStatuses.contains(code)) {
            return config.spouseRelType;
        }
        if (config.divorceStatuses.contains(code)) {
            return config.divorceRelType;
        }
        return null;
    }

    /** 按关系类型分组批量写入，返回本次实际新增的边数。 */
    private static long writeBatch(Session session, List<Map<String, Object>> batch, Config config,
                                   Map<String, Long> createdByRel) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : batch) {
            String relType = (String) row.get("relType");
            groups.computeIfAbsent(relType, k -> new ArrayList<>()).add(row);
        }

        long created = 0;
        for (Map.Entry<String, List<Map<String, Object>>> e : groups.entrySet()) {
            String relType = e.getKey();
            List<Map<String, Object>> rows = e.getValue();
            long c = session.executeWrite(tx -> tx.run(buildWriteQuery(relType, config), Map.of("rows", rows))
                    .consume().counters().relationshipsCreated());
            created += c;
            createdByRel.merge(relType, c, Long::sum);
        }
        return created;
    }

    /** 生成批量写入语句；关系类型已由 Config 校验为合法标识符。 */
    private static String buildWriteQuery(String relType, Config config) {
        String nodeA = config.createMissingNodes
                ? "MERGE (a:Person {gmsfhm: row.a})"
                : "MATCH (a:Person {gmsfhm: row.a})";
        String nodeB = config.createMissingNodes
                ? "MERGE (b:Person {gmsfhm: row.b})"
                : "MATCH (b:Person {gmsfhm: row.b})";
        return "UNWIND $rows AS row "
                + nodeA + " "
                + nodeB + " "
                + "WITH a, b, row WHERE a <> b "
                + "MERGE (a)-[:" + relType + "]->(b)";
    }

    private static void logProgress(long total) {
        if (total > 0 && total % LOG_EVERY == 0) {
            System.out.printf("已生成 %d 万条关系...%n", total / 10000);
        }
    }

    private static void printSummary(Session session, Config config) {
        StringBuilder sb = new StringBuilder("== 库内汇总 ==  Person=").append(countNodes(session));
        Set<String> types = new LinkedHashSet<>();
        types.add(config.spouseRelType);
        types.add(config.divorceRelType);
        for (String type : types) {
            sb.append(", ").append(type).append("=").append(countRel(session, type));
        }
        System.out.println(sb);
    }

    private static long countNodes(Session session) {
        Result r = session.run("MATCH (p:Person) RETURN count(p) AS c");
        return r.single().get("c").asLong();
    }

    private static long countRel(Session session, String relType) {
        Result r = session.run("MATCH ()-[r:" + relType + "]->() RETURN count(r) AS c");
        return r.single().get("c").asLong();
    }

    private static String quoteAll(Set<String> values) {
        return values.stream()
                .map(v -> "'" + v.replace("'", "''") + "'")
                .collect(Collectors.joining(", "));
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }
}
