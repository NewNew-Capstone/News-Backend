package com.example.news.domain.graph.node;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

import java.time.LocalDate;

@Node("Issue")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueNode {

    @Id
    @Property("issue_id")
    private Long clusterId;

    // Python이 keyword, name 두 필드 모두 참조하므로 둘 다 저장
    @Property("keyword")
    private String searchKeyword;

    @Property("name")
    private String name;

    @Property("cluster_type")
    private String clusterType;

    @Property("period_start_date")
    private LocalDate periodStartDate;

    @Property("period_end_date")
    private LocalDate periodEndDate;
}
