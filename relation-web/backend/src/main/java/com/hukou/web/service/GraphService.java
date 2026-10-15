package com.hukou.web.service;

import com.hukou.web.dto.GraphEdge;
import com.hukou.web.dto.GraphNode;
import com.hukou.web.dto.GraphResult;
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

@Service
public class GraphService {

    private static final Map<String, String> REL_LABELS = Map.of(
            "FATHER_OF", "父亲",
            "MOTHER_OF", "母亲",
            "MARRIED_TO", "配偶");

    private final Driver driver;

    public GraphService(Driver driver) {
        this.driver = driver;
    }

    /** 需求一：查询一个人的近亲属（配偶、父母、子女、兄弟姐妹） */
    public GraphResult getDirectRelatives(String gmsfhm) {
        String query = """
                MATCH (p:Person {gmsfhm: $gmsfhm})
                OPTIONAL MATCH (p)-[:MARRIED_TO]-(sp:Person)
                OPTIONAL MATCH (p)<-[:FATHER_OF|MOTHER_OF]-(par:Person)
                OPTIONAL MATCH (p)-[:FATHER_OF|MOTHER_OF]->(child:Person)
                OPTIONAL MATCH (p)<-[:FATHER_OF|MOTHER_OF]-(:Person)-[:FATHER_OF|MOTHER_OF]->(sib:Person)
                WHERE sib <> p
                OPTIONAL MATCH (sib)-[:MARRIED_TO]-(sibSpouse:Person)
                OPTIONAL MATCH (sib)-[:FATHER_OF|MOTHER_OF]->(sibChild:Person)
                WITH p,
                  collect(DISTINCT sp) + collect(DISTINCT par) + collect(DISTINCT child)
                    + collect(DISTINCT sib) + collect(DISTINCT sibSpouse) + collect(DISTINCT sibChild) + [p] AS allNodes
                UNWIND allNodes AS n
                WITH p, collect(DISTINCT n) AS nodes
                UNWIND nodes AS n
                OPTIONAL MATCH (n)-[r:FATHER_OF|MOTHER_OF|MARRIED_TO]-(m)
                WHERE m IN nodes
                WITH p, nodes, collect(DISTINCT r) AS rels
                RETURN
                  [n IN nodes | {id: n.rkbm, name: n.xm, gmsfhm: n.gmsfhm, xbdm: n.xbdm, yhzgxdm: n.yhzgxdm, center: n = p}] AS nodes,
                  [r IN rels | {source: startNode(r).rkbm, target: endNode(r).rkbm, type: type(r)}] AS edges
                """;

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
            return new GraphResult(true, nodes, edges,
                    centerName + " 的亲属（配偶、父母、子女、兄弟姐妹及其配偶、子女）");
        }
    }

    /** 需求二：查询任意两人的关系路径 */
    public GraphResult getRelationPath(String from, String to) {
        // 同一个人
        if (from.equals(to)) {
            return getSinglePerson(from);
        }

        String query = """
                MATCH (a:Person {gmsfhm: $from}), (b:Person {gmsfhm: $to})
                MATCH path = shortestPath((a)-[*..8]-(b))
                WITH a, b, nodes(path) AS ns, relationships(path) AS rs
                RETURN
                  [n IN ns | {id: n.rkbm, name: n.xm, gmsfhm: n.gmsfhm, xbdm: n.xbdm, endpoint: n = a OR n = b}] AS nodes,
                  [r IN rs | {source: startNode(r).rkbm, target: endNode(r).rkbm, type: type(r)}] AS edges
                """;

        try (Session session = driver.session()) {
            Result result = session.run(query, Map.of("from", from, "to", to));
            if (!result.hasNext()) {
                return new GraphResult(false, List.of(), List.of(),
                        "两人之间不存在亲属关系");
            }
            Record rec = result.single();
            List<GraphNode> nodes = buildNodes(rec);
            List<GraphEdge> edges = buildEdges(rec);
            return new GraphResult(true, nodes, edges, describePath(nodes, edges));
        }
    }

    private GraphResult getSinglePerson(String gmsfhm) {
        String query = """
                MATCH (n:Person {gmsfhm: $gmsfhm})
                RETURN n.rkbm AS id, n.xm AS name, n.gmsfhm AS gmsfhm, n.xbdm AS xbdm
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
            return new GraphResult(true, List.of(node), List.of(),
                    node.name() + " 与 " + node.name() + " 是同一个人");
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
            if ("MARRIED_TO".equals(e.type())) {
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
