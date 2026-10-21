package org.dromara.web.service;

import org.dromara.web.domain.vo.GraphEdge;
import org.dromara.web.domain.vo.GraphNode;
import org.dromara.web.domain.vo.GraphResult;
import org.dromara.web.domain.vo.GraphStats;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 户籍关系图谱查询服务
 *
 * @author hukou
 */
@Service
public class GraphService {

    private static final Map<String, String> REL_LABELS = Map.ofEntries(
        Map.entry("FATHER_OF", "父亲"),
        Map.entry("MOTHER_OF", "母亲"),
        Map.entry("MARRIED_TO", "配偶"),
        Map.entry("SIBLING_OF", "兄弟姐妹"),
        Map.entry("GRANDPARENT_OF", "祖辈"),
        Map.entry("GRANDCHILD_OF", "孙子/孙女"),
        Map.entry("MATERNAL_GRANDCHILD_OF", "外孙/外孙女"),
        Map.entry("GREAT_GRANDCHILD_OF", "曾孙/曾孙女"),
        Map.entry("PARENT_SIBLING_OF", "伯叔舅姑姨"),
        Map.entry("NEPHEW_OF", "侄甥"),
        Map.entry("IN_LAW", "姻亲"),
        Map.entry("OTHER_RELATIVE", "其他亲属"));

    /** 结构性关系（导入器 + 补充工具都会产生） */
    private static final String STRUCT_REL_TYPES = "FATHER_OF|MOTHER_OF|MARRIED_TO";
    /** 由 relation-supplement 依据“与户主关系”补充的关系类型 */
    private static final String NEW_REL_TYPES = "SIBLING_OF|GRANDPARENT_OF|GRANDCHILD_OF|MATERNAL_GRANDCHILD_OF|GREAT_GRANDCHILD_OF|PARENT_SIBLING_OF|NEPHEW_OF|IN_LAW|OTHER_RELATIVE";
    /** 全部关系类型竖线串，用于 Cypher 关系过滤 */
    private static final String REL_TYPES_PATTERN = STRUCT_REL_TYPES + "|" + NEW_REL_TYPES;
    /** 无向关系类型（描述与渲染时不区分方向） */
    private static final Set<String> UNDIRECTED = Set.of("MARRIED_TO", "SIBLING_OF", "IN_LAW", "OTHER_RELATIVE");

    /** 默认模式：查询配偶、父母、子女、兄弟姐妹及其配偶、子女，以及补充的直系/旁系亲属 */
    private static final String DEFAULT_DIRECT_QUERY = """
        MATCH (p:Person {gmsfhm: $gmsfhm})
        OPTIONAL MATCH (p)-[:MARRIED_TO]-(sp:Person)
        OPTIONAL MATCH (p)<-[:FATHER_OF|MOTHER_OF]-(par:Person)
        OPTIONAL MATCH (p)-[:FATHER_OF|MOTHER_OF]->(child:Person)
        OPTIONAL MATCH (p)<-[:FATHER_OF|MOTHER_OF]-(:Person)-[:FATHER_OF|MOTHER_OF]->(sib:Person)
        WHERE sib <> p
        OPTIONAL MATCH (sib)-[:MARRIED_TO]-(sibSpouse:Person)
        OPTIONAL MATCH (sib)-[:FATHER_OF|MOTHER_OF]->(sibChild:Person)
        OPTIONAL MATCH (p)-[:%s]-(nb:Person)
        WITH p,
          collect(DISTINCT sp) + collect(DISTINCT par) + collect(DISTINCT child)
            + collect(DISTINCT sib) + collect(DISTINCT sibSpouse) + collect(DISTINCT sibChild)
            + collect(DISTINCT nb) + [p] AS allNodes
        UNWIND allNodes AS n
        WITH p, collect(DISTINCT n) AS nodes
        UNWIND nodes AS n
        OPTIONAL MATCH (n)-[r:%s]-(m)
        WHERE m IN nodes
        WITH p, nodes, collect(DISTINCT r) AS rels
        RETURN
          [n IN nodes | {id: n.gmsfhm, name: n.xm, gmsfhm: n.gmsfhm, xbdm: n.xbdm, yhzgxdm: n.yhzgxdm, center: n = p}] AS nodes,
          [r IN rels | {source: startNode(r).gmsfhm, target: endNode(r).gmsfhm, type: type(r)}] AS edges
        """;

    private final Driver driver;

    public GraphService(Driver driver) {
        this.driver = driver;
    }

    /** 需求一：查询一个人的近亲属。
     *  level=0：默认模式（配偶、父母、子女、兄弟姐妹及其配偶、子女，以及补充的祖孙、伯叔舅姑姨、侄甥、姻亲、其他亲属）
     *  level=1..16：按级数扩展（1 级=直接关联节点，最多 16 级）
     *  level<0：不限级数（查询所有可达关联节点）
     */
    public GraphResult getDirectRelatives(String gmsfhm, int level) {
        String query = level == 0
                ? DEFAULT_DIRECT_QUERY.formatted(NEW_REL_TYPES, REL_TYPES_PATTERN)
                : buildLevelQuery(level);

        try (Session session = driver.session()) {
            Result result = session.run(query, Map.of("gmsfhm", gmsfhm));
            if (!result.hasNext()) {
                return new GraphResult(false, List.of(), List.of(), "未找到身份证号为 " + gmsfhm + " 的人员");
            }
            Record rec = result.single();
            List<GraphNode> nodes = buildNodes(rec);
            List<GraphEdge> edges = buildEdges(rec);
            String centerName = nodes.stream()
                .filter(GraphNode::center)
                .map(GraphNode::name)
                .findFirst()
                .orElse(gmsfhm);
            String desc;
            if (level == 0) {
                desc = centerName + " 的亲属（配偶、父母、子女、兄弟姐妹及其配偶、子女、祖父母、伯叔舅姑姨、侄甥、姻亲等）";
            } else if (level < 0) {
                desc = centerName + " 的全部关联亲属（不限级数）";
            } else {
                desc = centerName + " 的 " + Math.min(Math.max(level, 1), 16) + " 级关系";
            }
            return new GraphResult(true, nodes, edges, desc);
        }
    }

    private String buildLevelQuery(int level) {
        String pathRange = level < 0 ? "*" : "*1.." + Math.min(Math.max(level, 1), 16);
        return """
            MATCH (p:Person {gmsfhm: $gmsfhm})
            OPTIONAL MATCH (p)-[%s]-(n:Person)
            WITH p, collect(DISTINCT n) + [p] AS allNodes
            UNWIND allNodes AS n
            WITH p, collect(DISTINCT n) AS nodes
            UNWIND nodes AS n
            OPTIONAL MATCH (n)-[r:%s]-(m)
            WHERE m IN nodes
            WITH p, nodes, collect(DISTINCT r) AS rels
            RETURN
              [n IN nodes | {id: n.gmsfhm, name: n.xm, gmsfhm: n.gmsfhm, xbdm: n.xbdm, yhzgxdm: n.yhzgxdm, center: n = p}] AS nodes,
              [r IN rels | {source: startNode(r).gmsfhm, target: endNode(r).gmsfhm, type: type(r)}] AS edges
            """.formatted(pathRange, REL_TYPES_PATTERN);
    }

    /** 需求二：查询任意两人的关系路径。
     *  level=0：默认（最短路径，最多 16 级）
     *  level=1..16：限制最短路径的最大级数
     *  level<0：不限级数
     */
    public GraphResult getRelationPath(String from, String to, int level) {
        // 同一个人
        if (from.equals(to)) {
            return getSinglePerson(from);
        }

        String pathRange = level < 0 ? "*" : "*.." + (level <= 0 ? 16 : Math.min(Math.max(level, 1), 16));
        String query = """
            MATCH (a:Person {gmsfhm: $from}), (b:Person {gmsfhm: $to})
            MATCH path = shortestPath((a)-[%s]-(b))
            WITH a, b, nodes(path) AS ns, relationships(path) AS rs
            RETURN
              [n IN ns | {id: n.gmsfhm, name: n.xm, gmsfhm: n.gmsfhm, xbdm: n.xbdm, endpoint: n = a OR n = b}] AS nodes,
              [r IN rs | {source: startNode(r).gmsfhm, target: endNode(r).gmsfhm, type: type(r)}] AS edges
            """.formatted(pathRange);

        try (Session session = driver.session()) {
            Result result = session.run(query, Map.of("from", from, "to", to));
            if (!result.hasNext()) {
                return new GraphResult(false, List.of(), List.of(), "两人之间不存在亲属关系");
            }
            Record rec = result.single();
            List<GraphNode> nodes = buildNodes(rec);
            List<GraphEdge> edges = buildEdges(rec);
            return new GraphResult(true, nodes, edges, describePath(nodes, edges));
        }
    }

    /** 图谱统计：节点总数、关系总数、各关系类型数量、性别分布 */
    public GraphStats getStats() {
        try (Session session = driver.session()) {
            long nodeCount = session.run("MATCH (n:Person) RETURN count(n) AS c").single().get("c").asLong();
            long edgeCount = session.run("MATCH ()-[r]->() RETURN count(r) AS c").single().get("c").asLong();
            List<GraphStats.RelationTypeCount> relationTypes = session.run(
                "MATCH ()-[r]->() RETURN type(r) AS type, count(r) AS count ORDER BY count DESC"
            ).list(record -> {
                String type = record.get("type").asString();
                return new GraphStats.RelationTypeCount(type, REL_LABELS.getOrDefault(type, type), record.get("count").asLong());
            });
            List<GraphStats.GenderCount> genderStats = session.run(
                "MATCH (n:Person) WHERE n.xbdm IS NOT NULL RETURN n.xbdm AS code, count(n) AS count ORDER BY code"
            ).list(record -> {
                String code = record.get("code").asString();
                String label = "1".equals(code) ? "男" : "2".equals(code) ? "女" : "未知";
                return new GraphStats.GenderCount(code, label, record.get("count").asLong());
            });
            return new GraphStats(nodeCount, edgeCount, relationTypes, genderStats);
        }
    }

    private GraphResult getSinglePerson(String gmsfhm) {
        String query = """
            MATCH (n:Person {gmsfhm: $gmsfhm})
            RETURN n.gmsfhm AS id, n.xm AS name, n.gmsfhm AS gmsfhm, n.xbdm AS xbdm
            """;
        try (Session session = driver.session()) {
            Result result = session.run(query, Map.of("gmsfhm", gmsfhm));
            if (!result.hasNext()) {
                return new GraphResult(false, List.of(), List.of(), "未找到身份证号为 " + gmsfhm + " 的人员");
            }
            Record rec = result.single();
            GraphNode node = new GraphNode(
                rec.get("id").asString(),
                rec.get("name").asString(),
                rec.get("gmsfhm").asString(),
                rec.get("xbdm").asString(),
                null,
                true,
                true);
            return new GraphResult(true, List.of(node), List.of(), node.name() + " 与 " + node.name() + " 是同一个人");
        }
    }

    private List<GraphNode> buildNodes(Record rec) {
        List<Map<String, Object>> list = rec.get("nodes").asList(Value::asMap);
        List<GraphNode> nodes = new ArrayList<>();
        for (Map<String, Object> m : list) {
            nodes.add(new GraphNode(s(m.get("id")), s(m.get("name")),
                s(m.get("gmsfhm")), s(m.get("xbdm")), s(m.get("yhzgxdm")),
                Boolean.TRUE.equals(m.get("center")),
                Boolean.TRUE.equals(m.get("endpoint"))));
        }
        return nodes;
    }

    private List<GraphEdge> buildEdges(Record rec) {
        List<Map<String, Object>> list = rec.get("edges").asList(Value::asMap);
        List<GraphEdge> edges = new ArrayList<>();
        for (Map<String, Object> m : list) {
            String type = s(m.get("type"));
            edges.add(new GraphEdge(s(m.get("source")), s(m.get("target")),
                type, REL_LABELS.getOrDefault(type, type)));
        }
        return edges;
    }

    /** 根据路径生成可读的关系描述 */
    private String describePath(List<GraphNode> nodes, List<GraphEdge> edges) {
        Map<String, String> nameById = new HashMap<>();
        for (GraphNode n : nodes) {
            nameById.put(n.id(), n.name());
        }
        String fromName = nodes.get(0).name();
        String toName = nodes.get(nodes.size() - 1).name();
        if (edges.isEmpty()) {
            return fromName + " 与 " + toName + " 是同一个人";
        }
        List<String> steps = new ArrayList<>();
        for (GraphEdge e : edges) {
            String src = nameById.getOrDefault(e.source(), e.source());
            String tgt = nameById.getOrDefault(e.target(), e.target());
            String label = e.label();
            if (UNDIRECTED.contains(e.type())) {
                steps.add(src + " 与 " + tgt + " 是" + label);
            } else {
                steps.add(src + " 是 " + tgt + " 的" + label);
            }
        }
        return fromName + " 与 " + toName + " 存在关系：" + String.join("；", steps);
    }

    private static String s(Object o) {
        return o == null ? null : o.toString();
    }
}
