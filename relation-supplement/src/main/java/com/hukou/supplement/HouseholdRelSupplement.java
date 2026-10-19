package com.hukou.supplement;

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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 根据“与户主关系”(yhzgxdm) 补充 Neo4j 亲属关系。
 *
 * 关系代码字典来源：《户籍-与户主关系字段.xlsx》，此处仅抽取可映射到原有
 * 三类关系（FATHER_OF / MOTHER_OF / MARRIED_TO）的代码，其余不扩充。
 *
 * 处理逻辑：
 *   1. 以“户号 hh”分组，先找出每户的“户主”(yhzgxdm = '02')；
 *   2. 流式读取全部人员，对户内每个成员按与户主关系代码补充一条关系：
 *        配偶(10/11/12)             -> MARRIED_TO（户主 与 成员 无向）
 *        父亲(51/57)                -> FATHER_OF（成员 -> 户主）
 *        母亲(52/58)                -> MOTHER_OF（成员 -> 户主）
 *        父母(50/59)                -> 按成员性别推断 FATHER_OF / MOTHER_OF
 *        子女(20..29、30..39，除28/38) -> 按户主性别推断 FATHER_OF / MOTHER_OF（户主 -> 成员）
 *   其余代码（兄弟姐妹、祖孙、姻亲、非亲属等）不参与补充。
 *
 * 性别代码遵循 GB/T 2261.1：1=男，2=女；其余视为未知（跳过）。
 */
public class HouseholdRelSupplement {

    private static final int BATCH_SIZE = 10000;
    private static final long LOG_EVERY = 500000;

    private static final Set<String> SPOUSE = Set.of("10", "11", "12");
    private static final Set<String> FATHER = Set.of("51", "57");
    private static final Set<String> MOTHER = Set.of("52", "58");
    private static final Set<String> PARENT = Set.of("50", "59");
    private static final Set<String> CHILD = Set.of(
            "20", "21", "22", "23", "24", "25", "26", "27", "29",
            "30", "31", "32", "33", "34", "35", "36", "37", "39");

    public static void main(String[] args) throws Exception {
        Config config = Config.load(args);
        long start = System.currentTimeMillis();
        supplement(config);
        System.out.printf("全部完成，总耗时 %.1f 分钟%n", (System.currentTimeMillis() - start) / 60000.0);
    }

    private static void supplement(Config config) throws Exception {
        try (Driver driver = GraphDatabase.driver(config.neo4jUri, AuthTokens.basic(config.neo4jUser, config.neo4jPassword));
             Session session = driver.session();
             Connection conn = DriverManager.getConnection(config.mysqlUrl, config.mysqlUser, config.mysqlPassword);
             Statement stmt = conn.createStatement()) {

            System.out.printf("源表：%s，批大小：%d，Neo4j：%s%n", config.table, BATCH_SIZE, config.neo4jUri);

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

            // 1. 流式读取所有户主，建立 hh -> 户主 映射
            stmt.setFetchSize(Integer.MIN_VALUE);
            Map<String, HeadInfo> heads = new HashMap<>();
            String headSql = "SELECT hh, gmsfhm, xbdm FROM " + config.table + " WHERE yhzgxdm = '02'";
            try (ResultSet rs = stmt.executeQuery(headSql)) {
                while (rs.next()) {
                    String hh = rs.getString("hh");
                    String gmsfhm = rs.getString("gmsfhm");
                    if (isNotBlank(hh) && isNotBlank(gmsfhm)) {
                        heads.putIfAbsent(hh, new HeadInfo(gmsfhm, rs.getString("xbdm")));
                    }
                }
            }
            System.out.println("已读取户主数量：" + heads.size());

            // 2. 流式读取全部人员，按与户主关系补充
            List<Rel> batch = new ArrayList<>(BATCH_SIZE);
            long total = 0, father = 0, mother = 0, spouse = 0;
            String memberSql = "SELECT gmsfhm, xbdm, hh, yhzgxdm FROM " + config.table;
            try (ResultSet rs = stmt.executeQuery(memberSql)) {
                while (rs.next()) {
                    HeadInfo head = heads.get(rs.getString("hh"));
                    if (head == null) {
                        continue;
                    }
                    String gmsfhm = rs.getString("gmsfhm");
                    if (!isNotBlank(gmsfhm) || gmsfhm.equals(head.gmsfhm)) {
                        continue;
                    }
                    Rel rel = buildRel(rs.getString("yhzgxdm"), gmsfhm, rs.getString("xbdm"), head);
                    if (rel == null) {
                        continue;
                    }
                    batch.add(rel);
                    total++;
                    if (batch.size() >= BATCH_SIZE) {
                        Stats s = writeBatch(session, batch);
                        father += s.father;
                        mother += s.mother;
                        spouse += s.spouse;
                        batch.clear();
                        logProgress(total);
                    }
                }
                if (!batch.isEmpty()) {
                    Stats s = writeBatch(session, batch);
                    father += s.father;
                    mother += s.mother;
                    spouse += s.spouse;
                }
            }

            System.out.printf("补充关系完成：共生成 %d 条关系%n", total);
            System.out.printf("本次补充：FATHER_OF=%d, MOTHER_OF=%d, MARRIED_TO=%d%n", father, mother, spouse);
            printSummary(session);
        }
    }

    private static Rel buildRel(String code, String memberGmsfhm, String memberXbdm, HeadInfo head) {
        if (SPOUSE.contains(code)) {
            String a = memberGmsfhm.compareTo(head.gmsfhm) < 0 ? memberGmsfhm : head.gmsfhm;
            String b = memberGmsfhm.compareTo(head.gmsfhm) < 0 ? head.gmsfhm : memberGmsfhm;
            return Rel.spouse(a, b);
        }
        if (FATHER.contains(code)) {
            return Rel.father(memberGmsfhm, head.gmsfhm);
        }
        if (MOTHER.contains(code)) {
            return Rel.mother(memberGmsfhm, head.gmsfhm);
        }
        if (PARENT.contains(code)) {
            if ("1".equals(memberXbdm)) {
                return Rel.father(memberGmsfhm, head.gmsfhm);
            }
            if ("2".equals(memberXbdm)) {
                return Rel.mother(memberGmsfhm, head.gmsfhm);
            }
            return null;
        }
        if (CHILD.contains(code)) {
            if ("1".equals(head.xbdm)) {
                return Rel.father(head.gmsfhm, memberGmsfhm);
            }
            if ("2".equals(head.xbdm)) {
                return Rel.mother(head.gmsfhm, memberGmsfhm);
            }
            return null;
        }
        return null;
    }

    private static Stats writeBatch(Session session, List<Rel> rels) {
        return session.executeWrite(tx -> {
            List<Map<String, Object>> father = new ArrayList<>();
            List<Map<String, Object>> mother = new ArrayList<>();
            List<Map<String, Object>> spouse = new ArrayList<>();
            for (Rel r : rels) {
                switch (r.type) {
                    case FATHER -> father.add(map("parent", r.a, "child", r.b));
                    case MOTHER -> mother.add(map("parent", r.a, "child", r.b));
                    case SPOUSE -> spouse.add(map("a", r.a, "b", r.b));
                }
            }
            if (!father.isEmpty()) {
                tx.run("UNWIND $rows AS row "
                        + "MERGE (p:Person {gmsfhm: row.parent}) "
                        + "MERGE (c:Person {gmsfhm: row.child}) "
                        + "MERGE (p)-[:FATHER_OF]->(c)", Map.of("rows", father)).consume();
            }
            if (!mother.isEmpty()) {
                tx.run("UNWIND $rows AS row "
                        + "MERGE (p:Person {gmsfhm: row.parent}) "
                        + "MERGE (c:Person {gmsfhm: row.child}) "
                        + "MERGE (p)-[:MOTHER_OF]->(c)", Map.of("rows", mother)).consume();
            }
            if (!spouse.isEmpty()) {
                tx.run("UNWIND $rows AS row "
                        + "MERGE (a:Person {gmsfhm: row.a}) "
                        + "MERGE (b:Person {gmsfhm: row.b}) "
                        + "MERGE (a)-[:MARRIED_TO]->(b)", Map.of("rows", spouse)).consume();
            }
            return new Stats(father.size(), mother.size(), spouse.size());
        });
    }

    private static void logProgress(long total) {
        if (total > 0 && total % LOG_EVERY == 0) {
            System.out.printf("已生成 %d 万条关系...%n", total / 10000);
        }
    }

    private static void printSummary(Session session) {
        System.out.printf("== 库内汇总 ==  Person=%d, FATHER_OF=%d, MOTHER_OF=%d, MARRIED_TO=%d%n",
                countNodes(session), countRel(session, "FATHER_OF"),
                countRel(session, "MOTHER_OF"), countRel(session, "MARRIED_TO"));
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

    private enum Type { FATHER, MOTHER, SPOUSE }

    private record Rel(Type type, String a, String b) {
        static Rel father(String parent, String child) {
            return new Rel(Type.FATHER, parent, child);
        }

        static Rel mother(String parent, String child) {
            return new Rel(Type.MOTHER, parent, child);
        }

        static Rel spouse(String a, String b) {
            return new Rel(Type.SPOUSE, a, b);
        }
    }

    private record HeadInfo(String gmsfhm, String xbdm) {
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
