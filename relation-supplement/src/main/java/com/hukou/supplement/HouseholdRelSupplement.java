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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 根据“与户主关系”(yhzgxdm) 补充 Neo4j 亲属关系。
 *
 * 关系代码字典来源：《户籍-与户主关系字段.xlsx》(type_code=CD000579)。
 *
 * 处理逻辑：
 *   1. 以“户号 hh”分组，先找出每户的“户主”(yhzgxdm = '02')；
 *   2. 流式读取全部人员，对户内每个成员按与户主关系代码补充一条关系；
 *   3. 仅在“成员与户主在图谱中路径不可达”时补充（有界深度 guard，见 Config#guardMaxDepth），
 *      已可达则跳过；新建的边打 origin='supplement' 标记，guard 会忽略这些边，
 *      因此可重复运行且结果与批次顺序无关。
 *
 * 关系类型与代码映射（有向关系 src 为标签主语，无向关系按 gmsfhm 字典序规范化）：
 *   配偶(10/11/12)                       -> MARRIED_TO           无向
 *   父亲(51/57)                          -> FATHER_OF            成员->户主
 *   母亲(52/58)                          -> MOTHER_OF            成员->户主
 *   父母(50/59)                          -> 按成员性别推断 FATHER_OF / MOTHER_OF
 *   子女(20..27、29、30..37、39)          -> 按户主性别推断 FATHER_OF / MOTHER_OF（户主->成员）
 *   女婿/儿媳(28/38)、公婆岳父母(53..56)、
 *     配偶的祖辈(65/68)、嫂弟媳姐夫妹夫(72/74/76/78)、
 *   孙媳孙女婿(45/46)                  -> IN_LAW               无向
 *   内孙/孙女(41/42)                    -> GRANDCHILD_OF        成员->户主
 *   外孙/外孙女(43/44)                  -> MATERNAL_GRANDCHILD_OF 成员->户主
 *   曾孙(47/48)                         -> GREAT_GRANDCHILD_OF  成员->户主（代差3）
 *   祖辈(60..64、66/67、69)              -> GRANDPARENT_OF       成员->户主（含曾祖，代差近似）
 *   兄弟姐妹(70/71/73/75/77/79)          -> SIBLING_OF           无向
 *   伯叔舅姑姨及其配偶(81..90)            -> PARENT_SIBLING_OF    成员->户主
 *   侄甥(93..96)                         -> NEPHEW_OF            成员->户主
 *   堂表兄弟姐妹(91/92)、其他亲属(97)      -> OTHER_RELATIVE       无向
 *   户主/本人(01/02/03)、保姆(98)、非亲属(99) -> 不建边
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
    private static final Set<String> IN_LAW = Set.of(
            "28", "38", "45", "46", "53", "54", "55", "56", "65", "68", "72", "74", "76", "78");
    /** 内孙：孙子/孙女（41/42），成员 -> 户主 */
    private static final Set<String> GRANDCHILD = Set.of("41", "42");
    /** 外孙：外孙子/外孙女（43/44），成员 -> 户主 */
    private static final Set<String> MATERNAL_GRANDCHILD = Set.of("43", "44");
    /** 曾孙：47/48（代差3，不分内外），成员 -> 户主 */
    private static final Set<String> GREAT_GRANDCHILD = Set.of("47", "48");
    /** 祖辈：成员是户主的长辈，边方向 成员 -> 户主（含曾祖 66/67，代差近似） */
    private static final Set<String> GRAND_ASC = Set.of("60", "61", "62", "63", "64", "66", "67", "69");
    private static final Set<String> SIBLING = Set.of("70", "71", "73", "75", "77", "79");
    private static final Set<String> PARENT_SIBLING = Set.of(
            "81", "82", "83", "84", "85", "86", "87", "88", "89", "90");
    private static final Set<String> NEPHEW = Set.of("93", "94", "95", "96");
    private static final Set<String> OTHER = Set.of("91", "92", "97");

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
            System.out.printf("路径可达 guard：enabled=%s, maxDepth=%d, ignoreSupplementEdges=%s%n",
                    config.guardEnabled, config.guardMaxDepth, config.guardIgnoreSupplementEdges);

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
            EnumMap<Type, Long> createdByType = new EnumMap<>(Type.class);
            List<Rel> batch = new ArrayList<>(BATCH_SIZE);
            long candidates = 0, total = 0;
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
                    candidates++;
                    if (batch.size() >= BATCH_SIZE) {
                        total += writeBatch(session, batch, config, createdByType);
                        batch.clear();
                        logProgress(candidates);
                    }
                }
                if (!batch.isEmpty()) {
                    total += writeBatch(session, batch, config, createdByType);
                }
            }

            System.out.printf("候选关系：%d，实际新增：%d，跳过（已可达）：%d%n",
                    candidates, total, candidates - total);
            for (Type t : Type.values()) {
                long c = createdByType.getOrDefault(t, 0L);
                if (c > 0) {
                    System.out.printf("本次新增 %s = %d%n", t.relName, c);
                }
            }
            printSummary(session);
        }
    }

    private static Rel buildRel(String code, String memberGmsfhm, String memberXbdm, HeadInfo head) {
        if (code == null) {
            return null;
        }
        if (SPOUSE.contains(code)) {
            return Rel.of(Type.MARRIED_TO, memberGmsfhm, head.gmsfhm);
        }
        if (FATHER.contains(code)) {
            return Rel.of(Type.FATHER_OF, memberGmsfhm, head.gmsfhm);
        }
        if (MOTHER.contains(code)) {
            return Rel.of(Type.MOTHER_OF, memberGmsfhm, head.gmsfhm);
        }
        if (PARENT.contains(code)) {
            if ("1".equals(memberXbdm)) {
                return Rel.of(Type.FATHER_OF, memberGmsfhm, head.gmsfhm);
            }
            if ("2".equals(memberXbdm)) {
                return Rel.of(Type.MOTHER_OF, memberGmsfhm, head.gmsfhm);
            }
            return null;
        }
        if (CHILD.contains(code)) {
            if ("1".equals(head.xbdm)) {
                return Rel.of(Type.FATHER_OF, head.gmsfhm, memberGmsfhm);
            }
            if ("2".equals(head.xbdm)) {
                return Rel.of(Type.MOTHER_OF, head.gmsfhm, memberGmsfhm);
            }
            return null;
        }
        if (IN_LAW.contains(code)) {
            return Rel.of(Type.IN_LAW, memberGmsfhm, head.gmsfhm);
        }
        if (GRANDCHILD.contains(code)) {
            return Rel.of(Type.GRANDCHILD_OF, memberGmsfhm, head.gmsfhm);
        }
        if (MATERNAL_GRANDCHILD.contains(code)) {
            return Rel.of(Type.MATERNAL_GRANDCHILD_OF, memberGmsfhm, head.gmsfhm);
        }
        if (GREAT_GRANDCHILD.contains(code)) {
            return Rel.of(Type.GREAT_GRANDCHILD_OF, memberGmsfhm, head.gmsfhm);
        }
        if (GRAND_ASC.contains(code)) {
            return Rel.of(Type.GRANDPARENT_OF, memberGmsfhm, head.gmsfhm);
        }
        if (SIBLING.contains(code)) {
            return Rel.of(Type.SIBLING_OF, memberGmsfhm, head.gmsfhm);
        }
        if (PARENT_SIBLING.contains(code)) {
            return Rel.of(Type.PARENT_SIBLING_OF, memberGmsfhm, head.gmsfhm);
        }
        if (NEPHEW.contains(code)) {
            return Rel.of(Type.NEPHEW_OF, memberGmsfhm, head.gmsfhm);
        }
        if (OTHER.contains(code)) {
            return Rel.of(Type.OTHER_RELATIVE, memberGmsfhm, head.gmsfhm);
        }
        return null;
    }

    /**
     * 批量写入一组关系，返回本次实际新增的边数（guard 判定为“已可达”的会被跳过）。
     */
    private static long writeBatch(Session session, List<Rel> rels, Config config,
                                   EnumMap<Type, Long> createdByType) {
        return session.executeWrite(tx -> {
            EnumMap<Type, List<Map<String, Object>>> groups = new EnumMap<>(Type.class);
            for (Rel r : rels) {
                groups.computeIfAbsent(r.type, k -> new ArrayList<>()).add(map("a", r.a, "b", r.b));
            }
            long created = 0;
            for (Map.Entry<Type, List<Map<String, Object>>> e : groups.entrySet()) {
                Type type = e.getKey();
                Result result = tx.run(buildWriteQuery(type, config), Map.of("rows", e.getValue()));
                long c = result.consume().counters().relationshipsCreated();
                created += c;
                createdByType.merge(type, c, Long::sum);
            }
            return created;
        });
    }

    /** 生成带“路径可达 guard”的写入语句；关系类型名由枚举内插，不含用户输入。 */
    private static String buildWriteQuery(Type type, Config config) {
        String merge = "MERGE (a)-[rel:" + type.relName + "]->(b) ON CREATE SET rel.origin = 'supplement'";
        if (!config.guardEnabled) {
            return "UNWIND $rows AS row "
                    + "MERGE (a:Person {gmsfhm: row.a}) "
                    + "MERGE (b:Person {gmsfhm: row.b}) "
                    + "WITH a, b WHERE a <> b "
                    + merge;
        }
        String existsFilter = config.guardIgnoreSupplementEdges
                ? " WHERE all(r IN relationships(p) WHERE r.origin IS NULL OR r.origin <> 'supplement')"
                : "";
        return "UNWIND $rows AS row "
                + "MERGE (a:Person {gmsfhm: row.a}) "
                + "MERGE (b:Person {gmsfhm: row.b}) "
                + "WITH a, b WHERE a <> b "
                + "AND NOT EXISTS { MATCH p = (a)-[*1.." + config.guardMaxDepth + "]-(b)"
                + existsFilter + " } "
                + merge;
    }

    private static void logProgress(long total) {
        if (total > 0 && total % LOG_EVERY == 0) {
            System.out.printf("已生成 %d 万条关系...%n", total / 10000);
        }
    }

    private static void printSummary(Session session) {
        StringBuilder sb = new StringBuilder("== 库内汇总 ==  Person=").append(countNodes(session));
        for (Type t : Type.values()) {
            sb.append(", ").append(t.relName).append("=").append(countRel(session, t.relName));
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

    private static boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static Map<String, Object> map(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new HashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    private enum Type {
        FATHER_OF("FATHER_OF", false),
        MOTHER_OF("MOTHER_OF", false),
        MARRIED_TO("MARRIED_TO", true),
        SIBLING_OF("SIBLING_OF", true),
        GRANDPARENT_OF("GRANDPARENT_OF", false),
        GRANDCHILD_OF("GRANDCHILD_OF", false),
        MATERNAL_GRANDCHILD_OF("MATERNAL_GRANDCHILD_OF", false),
        GREAT_GRANDCHILD_OF("GREAT_GRANDCHILD_OF", false),
        PARENT_SIBLING_OF("PARENT_SIBLING_OF", false),
        NEPHEW_OF("NEPHEW_OF", false),
        IN_LAW("IN_LAW", true),
        OTHER_RELATIVE("OTHER_RELATIVE", true);

        final String relName;
        final boolean undirected;

        Type(String relName, boolean undirected) {
            this.relName = relName;
            this.undirected = undirected;
        }
    }

    private record Rel(Type type, String a, String b) {
        /** 无向关系按 gmsfhm 字典序规范化，保证同一对节点只建一条边。 */
        static Rel of(Type type, String a, String b) {
            if (type.undirected && a.compareTo(b) > 0) {
                return new Rel(type, b, a);
            }
            return new Rel(type, a, b);
        }
    }

    private record HeadInfo(String gmsfhm, String xbdm) {
    }
}
