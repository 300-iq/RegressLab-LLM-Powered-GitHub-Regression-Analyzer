package dev.regresslab.ai;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class AiJson {
    private AiJson() {}
    public static ObjectMapper reader(ObjectMapper mapper) {
        var copy = mapper.copy();
        copy.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxStringLength(65536).maxNestingDepth(16).maxNameLength(200).maxNumberLength(400).build());
        return copy;
    }
}
