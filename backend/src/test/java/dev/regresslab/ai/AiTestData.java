package dev.regresslab.ai;

import dev.regresslab.ai.AiDtos.*;
import dev.regresslab.analysis.AnalysisDtos.*;
import dev.regresslab.domain.ArtifactDtos.*;
import dev.regresslab.domain.ApiDtos.SampleDto;
import java.time.Instant;
import java.util.*;

final class AiTestData {
    static final UUID COMPARISON = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static Input input() { return input(20); }
    static Input input(double effect) {
        var stats = new Statistics(3,100,100,1.,.01);
        var arm = new ArmSummary("ns/op",Direction.LOWER,stats,stats,List.of(99.,100.,101.));
        var result = new Result("fixture.operation","ns/op",Direction.LOWER,arm,arm,effect,null,Classification.INCONCLUSIVE,
                "Insufficient independent forks",List.of("TOO_FEW_FORKS"),List.of(),List.of("No causal attribution"));
        var analysis = new AnalysisDto(UUID.randomUUID(),COMPARISON,"fork-bootstrap-v2","a".repeat(64),"b".repeat(64),Policy.defaults(5,1),result,DataOrigin.REAL,"GITHUB_METADATA",Instant.EPOCH);
        var file = new ChangedFile("src/Example.java","src/Example.java","@@ -2 +2 @@\n-return cached;\n+return scan();",
                List.of(new ChangedLine(Side.BASELINE,2,"return cached;"),new ChangedLine(Side.CANDIDATE,2,"return scan();")));
        return new Input(new Diff("fixture/demo",1,1,"a".repeat(40),"b".repeat(40),List.of(file)),
                new Evidence(analysis,"https://github.com/fixture/demo/actions/runs/1","https://github.com/fixture/demo/actions/runs/1",
                        List.of(new SampleDto(0,0,100)),List.of(new SampleDto(0,0,100)),"c".repeat(64),"c".repeat(64),com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()));
    }
    static ModelReport report() {
        return new ModelReport(List.of(new Hypothesis("Possible repeated traversal",List.of(new Reference("src/Example.java",Side.CANDIDATE,2)),
                COMPARISON,"Scanning may increase work","Profile both snapshots under matched conditions","No profiler or adequate forks")),List.of("More independent forks and allocation profiles"));
    }
}
