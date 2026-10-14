package dev.regresslab.ai;

import com.fasterxml.jackson.databind.JsonNode;
import dev.regresslab.ai.AiDtos.*;
import dev.regresslab.api.ApiProblem;
import dev.regresslab.github.GithubApi;
import dev.regresslab.security.LocalIngestionSettings;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PrDiffService {
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");
    private final GithubApi github;
    private final LocalIngestionSettings authorized;
    public PrDiffService(GithubApi github, LocalIngestionSettings authorized) { this.github = github; this.authorized = authorized; }
    public Diff fetch(String repo, int number, String baseline, String candidate) {
        if (!authorized.enabled() || !repo.equals(authorized.repositoryFullName()))
            throw new ApiProblem(HttpStatus.FORBIDDEN,"REPOSITORY_NOT_AUTHORIZED","Repository is not authorized for AI investigation.");
        if (number < 1 || !baseline.matches("[0-9a-f]{40}") || !candidate.matches("[0-9a-f]{40}")) mismatch();
        String prefix = "/repos/" + repo;
        var identity = github.get(prefix);
        if (identity.path("id").asLong() != authorized.repositoryGithubId()
                || !identity.path("full_name").asText().equals(repo) || !identity.path("private").isBoolean() || identity.path("private").asBoolean()) mismatch();
        var pr = github.get(prefix + "/pulls/" + number);
        verifyPr(pr, number, baseline, candidate);
        int fileCount = pr.path("changed_files").asInt(-1);
        if (fileCount > 20) tooLarge();
        var comparison = github.get(prefix + "/compare/" + baseline + "..." + candidate);
        if (!comparison.path("base_commit").path("sha").asText().equals(baseline)
                || !comparison.path("merge_base_commit").path("sha").asText().equals(baseline)
                || !comparison.path("files").isArray() || comparison.path("files").size() != fileCount) mismatch();
        var files = new ArrayList<ChangedFile>(); var paths = new HashSet<String>(); int bytes = 0;
        for (var file : comparison.path("files")) {
            String path = file.path("filename").asText(); String previous = file.path("previous_filename").asText(path);
            if (!validPath(path) || !validPath(previous) || !paths.add(path)) mismatch();
            if (!file.path("patch").isTextual()) throw ApiProblem.conflict("Binary or unavailable/truncated patches are not supported; no report was generated.");
            String patch = file.path("patch").asText();
            bytes += patch.getBytes(StandardCharsets.UTF_8).length + path.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 6000) tooLarge();
            var lines = parsePatch(patch);
            long added = lines.stream().filter(l -> l.side() == Side.CANDIDATE).count();
            long deleted = lines.size() - added;
            if (added != file.path("additions").asInt(-1) || deleted != file.path("deletions").asInt(-1)) mismatch();
            files.add(new ChangedFile(path, previous, patch, lines));
        }
        verifyPr(github.get(prefix + "/pulls/" + number), number, baseline, candidate);
        if (files.isEmpty() || files.stream().allMatch(f -> f.changedLines().isEmpty()))
            throw ApiProblem.conflict("No supported changed-code lines are available.");
        return new Diff(repo, authorized.repositoryGithubId(), number, baseline, candidate, List.copyOf(files));
    }
    private void verifyPr(JsonNode pr, int number, String baseline, String candidate) {
        if (pr.path("number").asInt() != number || pr.path("base").path("repo").path("id").asLong() != authorized.repositoryGithubId()
                || !pr.path("base").path("sha").asText().equals(baseline) || !pr.path("head").path("sha").asText().equals(candidate)) mismatch();
    }
    static boolean validPath(String path) {
        return path.length() <= 300 && !path.startsWith("/") && !path.contains("\\")
                && Arrays.stream(path.split("/", -1)).noneMatch(p -> p.isEmpty() || p.equals(".") || p.equals(".."))
                && path.codePoints().noneMatch(Character::isISOControl);
    }
    static List<ChangedLine> parsePatch(String patch) {
        var result = new ArrayList<ChangedLine>(); var seen = new HashSet<String>();
        int old = 0, next = 0, oldRemaining = 0, nextRemaining = 0; boolean hunk = false;
        try {
            for (String line : patch.split("\n")) {
                var match = HUNK.matcher(line);
                if (match.matches()) {
                    if (oldRemaining != 0 || nextRemaining != 0) mismatch();
                    old = Integer.parseInt(match.group(1)); next = Integer.parseInt(match.group(3));
                    oldRemaining = match.group(2) == null ? 1 : Integer.parseInt(match.group(2));
                    nextRemaining = match.group(4) == null ? 1 : Integer.parseInt(match.group(4)); hunk = true;
                } else if (line.equals("\\ No newline at end of file")) { /* metadata only */ }
                else {
                    if (!hunk || line.isEmpty()) mismatch();
                    char prefix = line.charAt(0);
                    if (prefix == '-' || prefix == '+') {
                        Side side = prefix == '-' ? Side.BASELINE : Side.CANDIDATE;
                        int location = prefix == '-' ? old : next;
                        if (location < 1 || !seen.add(side + ":" + location)) mismatch();
                        result.add(new ChangedLine(side, location, line.substring(1)));
                    } else if (prefix != ' ') mismatch();
                    if (prefix != '+') { old++; oldRemaining--; }
                    if (prefix != '-') { next++; nextRemaining--; }
                    if (oldRemaining < 0 || nextRemaining < 0) mismatch();
                }
            }
            if (!hunk || oldRemaining != 0 || nextRemaining != 0) mismatch();
        } catch (NumberFormatException e) { mismatch(); }
        return List.copyOf(result);
    }
    private static void mismatch() { throw ApiProblem.conflict("GitHub identity, current PR snapshot, merge base, or complete diff does not match the benchmark commits."); }
    private static void tooLarge() { throw new ApiProblem(HttpStatus.PAYLOAD_TOO_LARGE,"DIFF_TOO_LARGE","AI investigation supports at most 20 changed files and 6000 UTF-8 bytes of complete patches. Narrow the PR; no report was generated."); }
}
