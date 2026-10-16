package com.hukou.importer;

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
import java.util.List;
import java.util.Map;

/**
 * 将 MySQL（或兼容 MySQL 协议的 StarRocks）中的户籍基本信息表导入 Neo4j。
 *
 * 为支持千万级数据：
 *   1. 源库采用 JDBC 流式读取（setFetchSize），不一次性加载全部记录；
 *   2. 按批写入 Neo4j，节点与关系均用 UNWIND 批量合并，减少网络往返；
 *   3. 每批一个事务提交，内存占用与事务大小可控。
 *
 * 图模型：
 *   节点  (:Person)    —— 每个人一个节点，唯一键 rkbm（人口编码）
 *   关系  [:FATHER_OF] —— 父亲 -> 子女
 *         [:MOTHER_OF] —— 母亲 -> 子女
 *         [:MARRIED_TO]—— 配偶（无向，仅创建一次）
 */
public class MysqlToNeo4jImporter {

    /** 每批处理的行数 */
    private static final int BATCH_SIZE = 10000;
    /** 每处理多少行打印一次进度 */
    private static final long LOG_EVERY = 500000;

    /** 节点属性字段（不含 rkbm，rkbm 作为唯一键） */
    private static final String[] PROPS = {"gmsfhm", "xm", "xbdm", "csrq", "mzdm", "hh", "hlxdm", "yhzgxdm"};

    public static void main(String[] args) throws Exception {
        Config config = Config.load(args);
        long start = System.currentTimeMillis();
        importData(config);
        long costMs = System.currentTimeMillis() - start;
        System.out.printf("全部完成，总耗时 %.1f 分钟%n", costMs / 60000.0);
    }

    private static void importData(Config config) throws Exception {
        String sql = "SELECT rkbm, gmsfhm, xm, xbdm, csrq, mzdm, hh, hlxdm, yhzgxdm, "
                + "fq_rkbm, mq_rkbm, po_rkbm FROM " + config.table;

        try (Driver driver = GraphDatabase.driver(config.neo4jUri, AuthTokens.basic(config.neo4jUser, config.neo4jPassword));
             Session session = driver.session();
             Connection conn = DriverManager.getConnection(config.mysqlUrl, config.mysqlUser, config.mysqlPassword);
             Statement stmt = conn.createStatement()) {

            System.out.printf("源表：%s，批大小：%d，Neo4j：%s%n", config.table, BATCH_SIZE, config.neo4jUri);

            session.run("CREATE CONSTRAINT person_rkbm_unique IF NOT EXISTS "
                    + "FOR (p:Person) REQUIRE p.rkbm IS UNIQUE");
            session.run("CREATE INDEX person_gmsfhm_index IF NOT EXISTS "
                    + "FOR (p:Person) ON (p.gmsfhm)");
            System.out.println("Neo4j 唯一性约束与 gmsfhm 索引已就绪");

            // 大批量读取时调大源库查询超时（StarRocks 默认 300 秒，易超时）
            if (config.queryTimeoutSeconds > 0) {
                try {
                    stmt.execute("SET query_timeout = " + config.queryTimeoutSeconds);
                    System.out.println("已设置源库查询超时：" + config.queryTimeoutSeconds + " 秒");
                } catch (SQLException e) {
                    System.out.println("设置源库查询超时失败（非 StarRocks 可忽略）：" + e.getMessage());
                }
            }

            // 流式读取，避免一次性加载全部数据到内存
            stmt.setFetchSize(Integer.MIN_VALUE);
            System.out.println("开始流式读取并分批写入...");

            long total = 0;
            long father = 0, mother = 0, spouse = 0;
            List<Map<String, Object>> batch = new ArrayList<>(BATCH_SIZE);

            try (ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    batch.add(readRow(rs));
                    if (batch.size() >= BATCH_SIZE) {
                        Stats s = writeBatch(session, batch);
                        total += batch.size();
                        father += s.father;
                        mother += s.mother;
                        spouse += s.spouse;
                        batch.clear();
                        logProgress(total);
                    }
                }
                if (!batch.isEmpty()) {
                    Stats s = writeBatch(session, batch);
                    total += batch.size();
                    father += s.father;
                    mother += s.mother;
                    spouse += s.spouse;
                }
            }

            System.out.printf("读取并写入完成：共 %d 条记录%n", total);
            System.out.printf("本次关系：FATHER_OF=%d, MOTHER_OF=%d, MARRIED_TO=%d%n", father, mother, spouse);
            printSummary(session);
        }
    }

    private static Map<String, Object> readRow(ResultSet rs) throws SQLException {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("rkbm", rs.getString("rkbm"));
        for (String k : PROPS) {
            p.put(k, rs.getString(k));
        }
        p.put("fq_rkbm", rs.getString("fq_rkbm"));
        p.put("mq_rkbm", rs.getString("mq_rkbm"));
        p.put("po_rkbm", rs.getString("po_rkbm"));
        return p;
    }

    private static void logProgress(long total) {
        if (total > 0 && total % LOG_EVERY == 0) {
            System.out.printf("已处理 %d 万条...%n", total / 10000);
        }
    }

    private static Stats writeBatch(Session session, List<Map<String, Object>> batch) {
        return session.executeWrite(tx -> {
            // 1. 批量合并 Person 节点
            List<Map<String, Object>> nodeRows = new ArrayList<>(batch.size());
            for (Map<String, Object> p : batch) {
                Map<String, Object> row = new HashMap<>();
                row.put("rkbm", p.get("rkbm"));
                row.put("props", buildNodeProps(p));
                nodeRows.add(row);
            }
            tx.run("UNWIND $rows AS row MERGE (n:Person {rkbm: row.rkbm}) SET n += row.props",
                    Map.of("rows", nodeRows)).consume();

            // 2. 收集本批关系
            List<Map<String, Object>> fatherRows = new ArrayList<>();
            List<Map<String, Object>> motherRows = new ArrayList<>();
            List<Map<String, Object>> spouseRows = new ArrayList<>();
            for (Map<String, Object> p : batch) {
                String rkbm = (String) p.get("rkbm");
                String fq = (String) p.get("fq_rkbm");
                String mq = (String) p.get("mq_rkbm");
                String po = (String) p.get("po_rkbm");
                if (isNotBlank(fq)) {
                    fatherRows.add(map("child", rkbm, "parent", fq));
                }
                if (isNotBlank(mq)) {
                    motherRows.add(map("child", rkbm, "parent", mq));
                }
                // 配偶关系只按字典序创建一次，避免双向重复
                if (isNotBlank(po) && rkbm.compareTo(po) < 0) {
                    spouseRows.add(map("a", rkbm, "b", po));
                }
            }

            // 3. 批量合并关系（端点用 MERGE 兜底，避免父/母/配偶节点在后续批次才出现时丢边）
            if (!fatherRows.isEmpty()) {
                tx.run("UNWIND $rows AS row "
                        + "MERGE (a:Person {rkbm: row.child}) "
                        + "MERGE (b:Person {rkbm: row.parent}) "
                        + "MERGE (b)-[:FATHER_OF]->(a)", Map.of("rows", fatherRows)).consume();
            }
            if (!motherRows.isEmpty()) {
                tx.run("UNWIND $rows AS row "
                        + "MERGE (a:Person {rkbm: row.child}) "
                        + "MERGE (b:Person {rkbm: row.parent}) "
                        + "MERGE (b)-[:MOTHER_OF]->(a)", Map.of("rows", motherRows)).consume();
            }
            if (!spouseRows.isEmpty()) {
                tx.run("UNWIND $rows AS row "
                        + "MERGE (a:Person {rkbm: row.a}) "
                        + "MERGE (b:Person {rkbm: row.b}) "
                        + "MERGE (a)-[:MARRIED_TO]->(b)", Map.of("rows", spouseRows)).consume();
            }

            return new Stats(fatherRows.size(), motherRows.size(), spouseRows.size());
        });
    }

    /** 抽取节点属性（不含 rkbm，只保留非空字段） */
    private static Map<String, Object> buildNodeProps(Map<String, Object> p) {
        Map<String, Object> props = new LinkedHashMap<>();
        for (String k : PROPS) {
            Object v = p.get(k);
            if (v != null) {
                props.put(k, v);
            }
        }
        return props;
    }

    private static void printSummary(Session session) {
        long personCount = countNodes(session);
        long fatherCount = countRel(session, "FATHER_OF");
        long motherCount = countRel(session, "MOTHER_OF");
        long spouseCount = countRel(session, "MARRIED_TO");
        System.out.printf("== 库内汇总 ==  Person=%d, FATHER_OF=%d, MOTHER_OF=%d, MARRIED_TO=%d%n",
                personCount, fatherCount, motherCount, spouseCount);
    }

    private static long countNodes(Session session) {
        Result r = session.run("MATCH (p:Person) RETURN count(p) AS c");
        return r.single().get("c").asLong();
    }

    private static long countRel(Session session, String relType) {
        Result r = session.run("MATCH ()-[r:" + relType + "]->() RETURN count(r) AS c");
        return r.single().get("c").asLong();
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static Map<String, Object> map(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new HashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    private static final class Stats {
        final long father;
        final long mother;
        final long spouse;

        Stats(long father, long mother, long spouse) {
            this.father = father;
            this.mother = mother;
            this.spouse = spouse;
        }
    }
}
