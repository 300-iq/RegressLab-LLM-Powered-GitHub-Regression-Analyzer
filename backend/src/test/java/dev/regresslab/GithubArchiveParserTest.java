package dev.regresslab;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.regresslab.api.ApiProblem;
import dev.regresslab.github.GithubArchiveParser;
import dev.regresslab.ingestion.ArtifactValidator;
import jakarta.validation.Validation;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
class GithubArchiveParserTest {
    final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    final jakarta.validation.Validator validation=Validation.buildDefaultValidatorFactory().getValidator();
    final GithubArchiveParser parser=new GithubArchiveParser(mapper,new ArtifactValidator(mapper,validation),validation);
    static byte[] evidence(String arm) { try (var input=GithubArchiveParserTest.class.getResourceAsStream("/github/"+arm+".zip")) { return input.readAllBytes(); } catch(IOException e) { throw new UncheckedIOException(e); } }
    Map<String,byte[]> entries(byte[] bytes) throws IOException {
        var result=new LinkedHashMap<String,byte[]>();try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) { ZipEntry entry;while((entry=zip.getNextEntry())!=null) result.put(entry.getName(),zip.readAllBytes()); }return result;
    }
    static byte[] zip(Map<String,byte[]> entries) throws IOException {
        var output=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(output)) { for(var entry:entries.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry(); } }return output.toByteArray();
    }
    @Test void readsActualArchivedGithubObservationsAndAlternatingForks() {
        for(String arm:List.of("baseline","candidate")) {
            var archive=parser.parse(evidence(arm));assertThat(archive.artifact().benchmarks()).hasSize(2);
            assertThat(archive.artifact().benchmarks().getFirst().samples()).hasSize(15);
            assertThat(archive.execution().blocks()).hasSize(6);
            assertThat(archive.artifact().run().environment().runner()).contains("github-hosted");
        }
    }
    @Test void rejectsBadEncodingAndCompressedSize() {
        assertThatThrownBy(()->parser.decode(List.of("not base64!"))).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(()->parser.parse(new byte[524289])).isInstanceOf(ApiProblem.class);
    }
    @ParameterizedTest @ValueSource(strings={"../run.json","/run.json","script.sh","nested/run.json"})
    void rejectsPathsAndUnexpectedArchiveEntries(String name) throws Exception {
        var entries=entries(evidence("baseline"));entries.put(name,new byte[]{1});
        assertThatThrownBy(()->parser.parse(zip(entries))).isInstanceOf(ApiProblem.class);
    }
    @Test void rejectsMissingDuplicateCorruptAndExpandedOversizedEntries() throws Exception {
        var entries=entries(evidence("baseline"));entries.remove("execution.json");byte[] missing=zip(entries);assertThatThrownBy(()->parser.parse(missing)).isInstanceOf(ApiProblem.class);
        entries=entries(evidence("baseline"));entries.put("rux.json",entries.get("run.json"));
        byte[] duplicate=new String(zip(entries),StandardCharsets.ISO_8859_1).replace("rux.json","run.json").getBytes(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(()->parser.parse(duplicate)).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(()->parser.parse(new byte[]{1,2,3})).isInstanceOf(ApiProblem.class);
        var bomb=Map.of("run.json",new byte[1048577],"execution.json",new byte[]{1});
        assertThatThrownBy(()->parser.parse(zip(bomb))).isInstanceOf(ApiProblem.class);
    }
    @Test void recordsMalformedJmhOutputAsFailureEvenWhenTheProcessExitedSuccessfully() throws Exception {
        var entries=entries(evidence("baseline"));
        var run=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(entries.get("run.json"));
        var execution=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(entries.get("execution.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)run.path("run")).put("status","FAILED");
        run.set("benchmarks",mapper.createArrayNode());
        var block=(com.fasterxml.jackson.databind.node.ObjectNode)execution.path("blocks").get(0);
        block.put("status","BENCHMARK_FAILED").put("exitCode",0).put("error","JMH output is incomplete");
        block.set("observations",mapper.createArrayNode());
        // The reviewed producer skips later blocks after an arm fails.
        for(var later:execution.path("blocks"))
            if(later.path("arm").asText().equals("BASELINE") && later.path("forkIndex").asInt()>0) notRun((com.fasterxml.jackson.databind.node.ObjectNode)later);
        entries.put("run.json",mapper.writeValueAsBytes(run));
        entries.put("execution.json",mapper.writeValueAsBytes(execution));
        assertThat(parser.parse(zip(entries)).artifact().run().status().name()).isEqualTo("FAILED");
        block.put("error","");entries.put("execution.json",mapper.writeValueAsBytes(execution));
        assertThatThrownBy(()->parser.parse(zip(entries))).isInstanceOf(ApiProblem.class);
    }
    @ParameterizedTest @ValueSource(strings={"failedBuildButMeasured","continuedAfterFailure","neverStartedButDuration","skippedWithoutFailure","invalidBuildPhase"})
    void rejectsImpossibleExecutionSequences(String mutation) throws Exception {
        var entries=entries(evidence("baseline"));
        var run=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(entries.get("run.json"));
        var execution=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(entries.get("execution.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)run.path("run")).put("status","FAILED");run.set("benchmarks",mapper.createArrayNode());
        var build=(com.fasterxml.jackson.databind.node.ObjectNode)execution.path("builds").path("BASELINE");
        var first=(com.fasterxml.jackson.databind.node.ObjectNode)execution.path("blocks").get(0);
        if(mutation.equals("failedBuildButMeasured")) build.put("status","BUILD_FAILED").put("exitCode",1);
        if(mutation.equals("continuedAfterFailure")) first.put("status","BENCHMARK_FAILED").put("exitCode",1);
        if(mutation.equals("skippedWithoutFailure")) notRun(first);
        if(mutation.equals("neverStartedButDuration") || mutation.equals("invalidBuildPhase")) {
            build.put("status",mutation.equals("invalidBuildPhase")?"BENCHMARK_FAILED":"BUILD_FAILED").put("exitCode",1);
            for(var block:execution.path("blocks")) if(block.path("arm").asText().equals("BASELINE")) notRun((com.fasterxml.jackson.databind.node.ObjectNode)block);
            if(mutation.equals("neverStartedButDuration")) first.put("elapsedMs",12);
        }
        entries.put("run.json",mapper.writeValueAsBytes(run));entries.put("execution.json",mapper.writeValueAsBytes(execution));
        assertThatThrownBy(()->parser.parse(zip(entries))).isInstanceOf(ApiProblem.class);
    }
    private void notRun(com.fasterxml.jackson.databind.node.ObjectNode block) {
        block.put("status","NOT_RUN").put("elapsedMs",0);block.putNull("exitCode").putNull("startedAt").putNull("finishedAt");
        block.set("observations",mapper.createArrayNode());
    }
    @ParameterizedTest @ValueSource(strings={"buildLaunch","benchmarkLaunch","timeoutExitRace"})
    void preservesExplicitLaunchFailuresAndTimeoutExitRacesWithoutInventedCodes(String mutation) throws Exception {
        var entries=entries(evidence("baseline"));
        var run=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(entries.get("run.json"));
        var execution=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(entries.get("execution.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)run.path("run")).put("status","FAILED");run.set("benchmarks",mapper.createArrayNode());
        var failed=(com.fasterxml.jackson.databind.node.ObjectNode)(mutation.equals("buildLaunch")?execution.path("builds").path("BASELINE"):execution.path("blocks").get(0));
        failed.put("status",mutation.equals("buildLaunch")?"BUILD_FAILED":mutation.equals("timeoutExitRace")?"TIMEOUT":"BENCHMARK_FAILED");
        if(mutation.equals("timeoutExitRace")) failed.put("exitCode",0);else failed.putNull("exitCode");
        failed.put("error",mutation.equals("timeoutExitRace")?"Process exceeded its timeout":"Process could not start: FileNotFoundError");
        if(!mutation.equals("buildLaunch")) failed.set("observations",mapper.createArrayNode());
        for(var block:execution.path("blocks"))
            if(block.path("arm").asText().equals("BASELINE") && (mutation.equals("buildLaunch") || block.path("forkIndex").asInt()>0)) notRun((com.fasterxml.jackson.databind.node.ObjectNode)block);
        entries.put("run.json",mapper.writeValueAsBytes(run));entries.put("execution.json",mapper.writeValueAsBytes(execution));
        assertThat(parser.parse(zip(entries)).artifact().run().status().name()).isEqualTo("FAILED");
        failed.put("error","");entries.put("execution.json",mapper.writeValueAsBytes(execution));
        assertThatThrownBy(()->parser.parse(zip(entries))).isInstanceOf(ApiProblem.class);
    }
    @ParameterizedTest @ValueSource(strings={"order","sample","config","status","duplicateCase","unicode","duration"})
    void rejectsConflictingOrInvalidExecutionLedgers(String mutation) throws Exception {
        var entries=entries(evidence("baseline"));var run=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(entries.get("run.json"));
        var execution=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(entries.get("execution.json"));
        switch(mutation) {
            case "order" -> ((com.fasterxml.jackson.databind.node.ArrayNode)execution.path("order")).set(0,mapper.valueToTree("CANDIDATE"));
            case "sample" -> ((com.fasterxml.jackson.databind.node.ObjectNode)run.path("benchmarks").get(0).path("samples").get(0)).put("value",42);
            case "config" -> ((com.fasterxml.jackson.databind.node.ObjectNode)run.path("benchmarks").get(0).path("configuration")).put("warmupTimeMs",500);
            case "status" -> ((com.fasterxml.jackson.databind.node.ObjectNode)execution.path("blocks").get(0)).put("exitCode",1);
            case "duplicateCase" -> ((com.fasterxml.jackson.databind.node.ObjectNode)execution.path("blocks").get(0).path("observations").get(1)).put("identifier",execution.path("blocks").get(0).path("observations").get(0).path("identifier").asText());
            case "duration" -> ((com.fasterxml.jackson.databind.node.ObjectNode)execution.path("blocks").get(0)).put("elapsedMs",900001);
            case "unicode" -> ((com.fasterxml.jackson.databind.node.ObjectNode)execution.path("blocks").get(0)).put("error","\uD800");
            default -> throw new AssertionError();
        }
        entries.put("run.json",mapper.writeValueAsBytes(run));entries.put("execution.json",mapper.writeValueAsBytes(execution));
        assertThatThrownBy(()->parser.parse(zip(entries))).isInstanceOf(ApiProblem.class);
    }
}
