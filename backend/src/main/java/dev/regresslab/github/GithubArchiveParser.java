package dev.regresslab.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.regresslab.api.ApiProblem;
import dev.regresslab.domain.ArtifactDtos.*;
import dev.regresslab.github.GithubDtos.*;
import dev.regresslab.ingestion.ArtifactValidator;
import jakarta.validation.Validator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;

@Component
public class GithubArchiveParser {
    public static final int MAX_ARCHIVE_BYTES=524288;
    public static final Set<String> CASES=Set.of("dev.regresslab.demo.EventBenchmarks.countByCategory","dev.regresslab.demo.EventBenchmarks.distinctCategories");
    private final ObjectMapper mapper;
    private final ArtifactValidator artifacts;
    private final Validator validator;
    public GithubArchiveParser(ObjectMapper mapper, ArtifactValidator artifacts, Validator validator) {
        this.mapper=mapper; this.artifacts=artifacts; this.validator=validator;
    }
    public byte[] decode(List<String> chunks) {
        try {
            byte[] bytes=Base64.getDecoder().decode(String.join("",chunks));
            if (bytes.length==0 || bytes.length>MAX_ARCHIVE_BYTES) throw ApiProblem.invalid("Archive exceeds the compressed-size limit.");
            return bytes;
        } catch (IllegalArgumentException exception) { throw ApiProblem.invalid("Invalid archive encoding."); }
    }
    public Archive parse(byte[] bytes) {
        if (bytes.length==0 || bytes.length>MAX_ARCHIVE_BYTES) throw ApiProblem.invalid("Invalid archive size.");
        var entries=new HashMap<String,byte[]>();
        int total=0;
        try (var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry=zip.getNextEntry())!=null) {
                if (entry.isDirectory() || !Set.of("run.json","execution.json").contains(entry.getName()) || entries.containsKey(entry.getName()))
                    throw ApiProblem.invalid("Archive must contain exactly run.json and execution.json, without paths or duplicate entries.");
                byte[] content=zip.readNBytes(ArtifactValidator.MAX_BYTES-total+1);
                total+=content.length;
                if (total>ArtifactValidator.MAX_BYTES) throw ApiProblem.invalid("Expanded archive exceeds 1 MiB.");
                ArtifactValidator.requireUtf8(content);
                entries.put(entry.getName(),content);
            }
            if (!entries.keySet().equals(Set.of("run.json","execution.json"))) throw ApiProblem.invalid("Missing expected archive entries.");
            var artifact=artifacts.parse(entries.get("run.json"));
            var execution=mapper.readValue(entries.get("execution.json"),Execution.class);
            if (execution==null || !validator.validate(execution).isEmpty()) throw ApiProblem.invalid("Invalid execution ledger.");
            ArtifactValidator.rejectInvalidText(mapper.valueToTree(execution));
            validateExecution(artifact,execution);
            return new Archive(artifact,entries.get("run.json"),bytes,execution);
        } catch (IOException exception) { throw ApiProblem.invalid("Invalid ZIP archive or execution ledger."); }
    }
    private void validateExecution(Artifact artifact, Execution execution) {
        var first=artifact.run().workflowRunId()%2==0 ? Arm.BASELINE : Arm.CANDIDATE;
        var second=first==Arm.BASELINE ? Arm.CANDIDATE : Arm.BASELINE;
        var expected=List.of(first,second,second,first,first,second);
        if (!execution.order().equals(expected) || !execution.builds().keySet().equals(Set.of(Arm.BASELINE,Arm.CANDIDATE)))
            throw ApiProblem.invalid("Execution order/build arms do not match the reviewed protocol.");
        var active=new EnumMap<Arm,Boolean>(Arm.class);
        execution.builds().forEach((arm,build) -> {
            if (!Set.of(Status.COMPLETE,Status.BUILD_FAILED,Status.TIMEOUT).contains(build.status()))
                throw ApiProblem.invalid("Invalid build-phase status.");
            validStatus(build.status(),build.exitCode(),build.startedAt(),build.finishedAt(),build.elapsedMs(),build.error());
            active.put(arm,build.status()==Status.COMPLETE);
        });
        var indices=new EnumMap<Arm,Integer>(Arm.class); indices.put(Arm.BASELINE,0); indices.put(Arm.CANDIDATE,0);
        for (int i=0;i<6;i++) {
            var block=execution.blocks().get(i);
            if (block.arm()!=expected.get(i) || block.forkIndex()!=indices.get(block.arm())) throw ApiProblem.invalid("Invalid execution block identity.");
            indices.put(block.arm(),block.forkIndex()+1);
            if (block.status()==Status.BUILD_FAILED || ((block.status()!=Status.NOT_RUN)!=active.get(block.arm())))
                throw ApiProblem.invalid("Execution continues after a failure or skips a runnable block.");
            validStatus(block.status(),block.exitCode(),block.startedAt(),block.finishedAt(),block.elapsedMs(),block.error());
            if (block.status()!=Status.COMPLETE) active.put(block.arm(),false);
            var names=new HashSet<String>();
            for (var observation:block.observations()) {
                if (!CASES.contains(observation.identifier()) || !names.add(observation.identifier())
                        || observation.values().stream().anyMatch(value -> !Double.isFinite(value) || value<=0 || value>1e15))
                    throw ApiProblem.invalid("Invalid raw execution observations.");
            }
            if (block.status()==Status.COMPLETE && !names.equals(CASES)) throw ApiProblem.invalid("Completed block lacks benchmark cases.");
            if (block.status()==Status.NOT_RUN && !block.observations().isEmpty()) throw ApiProblem.invalid("Unexecuted block contains observations.");
        }
        Arm arm=artifact.run().commitSha().equals(artifact.pullRequest().baselineSha()) ? Arm.BASELINE : Arm.CANDIDATE;
        var blocks=execution.blocks().stream().filter(block -> block.arm()==arm).toList();
        boolean complete=execution.builds().get(arm).status()==Status.COMPLETE && blocks.stream().allMatch(block -> block.status()==Status.COMPLETE);
        if ((artifact.run().status()==RunStatus.COMPLETE)!=complete) throw ApiProblem.invalid("Run status does not match execution evidence.");
        if (complete && !artifact.benchmarks().stream().map(CaseInput::identifier).collect(java.util.stream.Collectors.toSet()).equals(CASES))
            throw ApiProblem.invalid("Run cases do not match the reviewed harness.");
        for (var benchmark:artifact.benchmarks()) {
            var config=benchmark.configuration();
            if (!benchmark.metricName().equals("average_time") || !benchmark.unit().equals("ns/op") || benchmark.direction()!=Direction.LOWER
                    || !benchmark.parameters().equals(Map.of("events","10000")) || config.forkCount()!=3 || config.warmupIterations()!=3
                    || config.measurementIterations()!=5 || config.warmupTimeMs()!=1000 || config.measurementTimeMs()!=1000
                    || !config.jvmArgs().equals(List.of("-Xms256m","-Xmx256m","-Dregresslab.harness="+execution.harnessSha256())))
                throw ApiProblem.invalid("Benchmark configuration differs from the reviewed pipeline.");
            for (var sample:benchmark.samples()) {
                double original=blocks.get(sample.forkIndex()).observations().stream().filter(o -> o.identifier().equals(benchmark.identifier())).findFirst().orElseThrow().values().get(sample.iterationIndex());
                if (Double.doubleToLongBits(original)!=Double.doubleToLongBits(sample.value())) throw ApiProblem.invalid("Samples differ from the execution ledger.");
            }
        }
    }
    private static void validStatus(Status status,Integer code,java.time.Instant started,java.time.Instant finished,long elapsed,String error) {
        if (status==Status.NOT_RUN) {
            if (code!=null || started!=null || finished!=null || elapsed!=0) throw ApiProblem.invalid("Unexecuted status has execution metadata.");
            return;
        }
        boolean explained=error!=null && !error.isBlank();
        boolean invalidCode=switch(status) {
            case COMPLETE -> code==null || code!=0 || explained;
            case TIMEOUT -> code==null || code==0 && !explained;
            case BUILD_FAILED -> code==null ? !explained : code==0;
            case BENCHMARK_FAILED -> (code==null || code==0) && !explained;
            case NOT_RUN -> false;
        };
        if (started==null || finished==null || finished.isBefore(started) || invalidCode)
            throw ApiProblem.invalid("Inconsistent execution status/timestamps.");
    }
}
