package dev.regresslab.evaluation;
import com.fasterxml.jackson.databind.ObjectMapper;
public final class RepeatedParsing {
    public int total(ObjectMapper mapper, String json) throws Exception {
        var tree = mapper.readTree(json);
        int sum = 0;
        for (int i = 0; i < 64; i++) sum += tree.path("value").asInt();
        return sum;
    }
}
