package dev.regresslab.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.regresslab.ai.AiDtos.*;
import dev.regresslab.api.ApiProblem;
import dev.regresslab.github.GithubApi;
import dev.regresslab.security.LocalIngestionSettings;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrDiffServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final GithubApi github = mock(GithubApi.class);
    private final PrDiffService service = new PrDiffService(github,new LocalIngestionSettings(true,"x".repeat(32),1,"fixture/demo",30));
    private final String prefix="/repos/fixture/demo", base="a".repeat(40), head="b".repeat(40);
    private void valid() throws Exception {
        when(github.get(prefix)).thenReturn(mapper.readTree("{\"id\":1,\"full_name\":\"fixture/demo\",\"private\":false}"));
        when(github.get(prefix+"/pulls/1")).thenReturn(mapper.readTree("{\"number\":1,\"changed_files\":1,\"base\":{\"sha\":\""+base+"\",\"repo\":{\"id\":1}},\"head\":{\"sha\":\""+head+"\"}}"));
        var comparison=mapper.createObjectNode(); comparison.putObject("base_commit").put("sha",base); comparison.putObject("merge_base_commit").put("sha",base);
        comparison.putArray("files").addObject().put("filename","src/Example.java").put("additions",1).put("deletions",1).put("patch","@@ -2 +2 @@\n-return cached;\n+return scan();");
        when(github.get(prefix+"/compare/"+base+"..."+head)).thenReturn(comparison);
    }
    @Test void obtainsCompleteAuthorizedDiffAndPreservesReferences() throws Exception {
        valid(); var diff=service.fetch("fixture/demo",1,base,head);
        assertThat(diff.files().getFirst().changedLines()).containsExactly(new ChangedLine(Side.BASELINE,2,"return cached;"),new ChangedLine(Side.CANDIDATE,2,"return scan();"));
        verify(github,times(2)).get(prefix+"/pulls/1");
    }
    @Test void rejectsUnauthorizedRepositoriesBeforeNetwork() {
        assertThatThrownBy(() -> service.fetch("evil/demo",1,base,head)).isInstanceOf(ApiProblem.class); verifyNoInteractions(github);
    }
    @Test void rejectsChangedSnapshotMergeBaseAndTruncatedPatch() throws Exception {
        valid(); var compare=github.get(prefix+"/compare/"+base+"..."+head);
        ((com.fasterxml.jackson.databind.node.ObjectNode)compare.path("merge_base_commit")).put("sha",head);
        assertThatThrownBy(() -> service.fetch("fixture/demo",1,base,head)).isInstanceOf(ApiProblem.class);
        valid(); var c=github.get(prefix+"/compare/"+base+"..."+head);
        ((com.fasterxml.jackson.databind.node.ObjectNode)c.path("files").get(0)).put("additions",2);
        assertThatThrownBy(() -> service.fetch("fixture/demo",1,base,head)).isInstanceOf(ApiProblem.class);
        valid(); var first=github.get(prefix+"/pulls/1"); var changed=first.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)changed.path("head")).put("sha",base);
        when(github.get(prefix+"/pulls/1")).thenReturn(first,changed);
        assertThatThrownBy(() -> service.fetch("fixture/demo",1,base,head)).isInstanceOf(ApiProblem.class);
    }
    @Test void rejectsOversizedDiffAndMissingPatch() throws Exception {
        valid(); var c=github.get(prefix+"/compare/"+base+"..."+head); var f=(com.fasterxml.jackson.databind.node.ObjectNode)c.path("files").get(0);
        f.put("patch","x".repeat(6001));
        assertThatThrownBy(() -> service.fetch("fixture/demo",1,base,head)).isInstanceOf(ApiProblem.class).hasMessageContaining("6000");
        f.remove("patch"); assertThatThrownBy(() -> service.fetch("fixture/demo",1,base,head)).isInstanceOf(ApiProblem.class).hasMessageContaining("Binary");
    }
    @Test void hunkParserHandlesAddedDeletedAndContextLinesWithoutMakingContextCitable() {
        var lines=PrDiffService.parsePatch("@@ -1,2 +1,3 @@\n unchanged\n-old\n+new\n+extra\n\\ No newline at end of file");
        assertThat(lines).extracting(ChangedLine::line).containsExactly(2,2,3);
        for (String patch : List.of("+fake","@@ -1,2 +1 @@\n-old\n+new","@@ -1 +1 @@\n-old\n+new\n@@ -1 +1 @@\n-old\n+new"))
            assertThatThrownBy(() -> PrDiffService.parsePatch(patch)).isInstanceOf(ApiProblem.class);
        assertThat(PrDiffService.validPath("../../file")).isFalse();
    }
}
