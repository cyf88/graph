package com.hukou.importer;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 MySQL hukou 库中的户籍基本信息表导入 Neo4j 图数据库。
 *
 * 图模型：
 *   节点  (:Person)    —— 每个人一个节点，唯一键 rkbm（人口编码）
 *   关系  [:FATHER_OF] —— 父亲 -> 子女
 *         [:MOTHER_OF] —— 母亲 -> 子女
 *         [:MARRIED_TO]—— 配偶（无向，仅创建一次）
 */
public class MysqlToNeo4jImporter {

    // ===== MySQL 配置 =====
    private static final String MYSQL_URL = "jdbc:mysql://172.17.43.25:3308/hukou"
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8";
    private static final String MYSQL_USER = "root";
    private static final String MYSQL_PASSWORD = "root";
    private static final String TABLE = "zl_rk_xzrk_pb_hj_jbxx";

    // ===== Neo4j 配置 =====
    private static final String NEO4J_URI = "bolt://172.17.43.137:7687";
    private static final String NEO4J_USER = "neo4j";
    private static final String NEO4J_PASSWORD = "neo4j123";

    public static void main(String[] args) throws Exception {
        List<Map<String, Object>> persons = loadPersons();
        System.out.println("从 MySQL 读取到 " + persons.size() + " 条人口记录");

        importToNeo4j(persons);
        System.out.println("导入 Neo4j 完成");
    }

    /** 从 MySQL 读取人口记录（含用于建边的父亲/母亲/配偶人口编码） */
    private static List<Map<String, Object>> loadPersons() throws Exception {
        String sql = "SELECT rkbm, gmsfhm, xm, xbdm, csrq, mzdm, hh, hlxdm, yhzgxdm, "
                + "fq_rkbm, mq_rkbm, po_rkbm FROM " + TABLE;

        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(MYSQL_URL, MYSQL_USER, MYSQL_PASSWORD);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("rkbm", rs.getString("rkbm"));
                p.put("gmsfhm", rs.getString("gmsfhm"));
                p.put("xm", rs.getString("xm"));
                p.put("xbdm", rs.getString("xbdm"));
                p.put("csrq", rs.getString("csrq"));
                p.put("mzdm", rs.getString("mzdm"));
                p.put("hh", rs.getString("hh"));
                p.put("hlxdm", rs.getString("hlxdm"));
                p.put("yhzgxdm", rs.getString("yhzgxdm"));
                p.put("fq_rkbm", rs.getString("fq_rkbm"));
                p.put("mq_rkbm", rs.getString("mq_rkbm"));
                p.put("po_rkbm", rs.getString("po_rkbm"));
                list.add(p);
            }
        }
        return list;
    }

    /** 写入 Neo4j：节点 + 关系 */
    private static void importToNeo4j(List<Map<String, Object>> persons) {
        try (Driver driver = GraphDatabase.driver(NEO4J_URI, AuthTokens.basic(NEO4J_USER, NEO4J_PASSWORD));
             Session session = driver.session()) {

            // 唯一性约束：保证 rkbm 唯一，加速 MERGE
            session.run("CREATE CONSTRAINT person_rkbm_unique IF NOT EXISTS "
                    + "FOR (p:Person) REQUIRE p.rkbm IS UNIQUE");

            // 阶段 1：创建 Person 节点
            for (Map<String, Object> p : persons) {
                Map<String, Object> props = buildNodeProps(p);
                Map<String, Object> params = new HashMap<>();
                params.put("rkbm", p.get("rkbm"));
                params.put("props", props);
                session.run("MERGE (n:Person {rkbm: $rkbm}) SET n += $props", params);
            }
            System.out.println("Person 节点创建完成");

            // 阶段 2：创建亲属关系
            int father = 0, mother = 0, spouse = 0;
            for (Map<String, Object> p : persons) {
                String rkbm = (String) p.get("rkbm");
                String fq = (String) p.get("fq_rkbm");
                String mq = (String) p.get("mq_rkbm");
                String po = (String) p.get("po_rkbm");

                if (isNotBlank(fq)) {
                    session.run("MATCH (a:Person {rkbm: $rkbm}), (b:Person {rkbm: $fq}) "
                            + "MERGE (b)-[:FATHER_OF]->(a)", map("rkbm", rkbm, "fq", fq));
                    father++;
                }
                if (isNotBlank(mq)) {
                    session.run("MATCH (a:Person {rkbm: $rkbm}), (b:Person {rkbm: $mq}) "
                            + "MERGE (b)-[:MOTHER_OF]->(a)", map("rkbm", rkbm, "mq", mq));
                    mother++;
                }
                // 配偶关系只按字典序创建一次，避免双向重复
                if (isNotBlank(po) && rkbm.compareTo(po) < 0) {
                    session.run("MATCH (a:Person {rkbm: $rkbm}), (b:Person {rkbm: $po}) "
                            + "MERGE (a)-[:MARRIED_TO]->(b)", map("rkbm", rkbm, "po", po));
                    spouse++;
                }
            }
            System.out.printf("关系创建完成：FATHER_OF=%d, MOTHER_OF=%d, MARRIED_TO=%d%n",
                    father, mother, spouse);

            printSummary(session);
        }
    }

    /** 抽取节点属性（不含 rkbm，rkbm 已由 MERGE 设置） */
    private static Map<String, Object> buildNodeProps(Map<String, Object> p) {
        Map<String, Object> props = new LinkedHashMap<>();
        String[] keys = {"gmsfhm", "xm", "xbdm", "csrq", "mzdm", "hh", "hlxdm", "yhzgxdm"};
        for (String k : keys) {
            Object v = p.get(k);
            if (v != null) {
                props.put(k, v);
            }
        }
        return props;
    }

    private static void printSummary(Session session) {
        Result r1 = session.run("MATCH (p:Person) RETURN count(p) AS c");
        Record rec = r1.single();
        long personCount = rec.get("c").asLong();

        long fatherCount = count(session, "FATHER_OF");
        long motherCount = count(session, "MOTHER_OF");
        long spouseCount = count(session, "MARRIED_TO");

        System.out.printf("== 导入结果 ==  Person=%d, FATHER_OF=%d, MOTHER_OF=%d, MARRIED_TO=%d%n",
                personCount, fatherCount, motherCount, spouseCount);
    }

    private static long count(Session session, String relType) {
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
}
