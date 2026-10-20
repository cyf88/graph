export interface GraphNode {
  id: string;
  name: string;
  gmsfhm: string;
  xbdm: string;
  yhzgxdm: string | null;
  center: boolean;
  endpoint: boolean;
}

export interface GraphEdge {
  source: string;
  target: string;
  type: string;
  label: string;
}

export interface GraphResult {
  hasRelation: boolean;
  nodes: GraphNode[];
  edges: GraphEdge[];
  description: string;
}

export interface RelationTypeCount {
  type: string;
  label: string;
  count: number;
}

export interface GenderCount {
  code: string;
  label: string;
  count: number;
}

export interface GraphStats {
  nodeCount: number;
  edgeCount: number;
  relationTypes: RelationTypeCount[];
  genderStats: GenderCount[];
}
