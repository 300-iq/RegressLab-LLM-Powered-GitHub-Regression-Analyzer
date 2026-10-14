package dev.regresslab.github;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;

final class GithubJson {
    static final int MAX_RESPONSE_BYTES = 1048576;
    private GithubJson() {}
    static ObjectMapper metadataMapper(ObjectMapper applicationMapper) {
        // GitHub descriptions/commit messages may exceed artifact-field limits.
        // Network bytes are bounded separately. Never relax the MVC/artifact mapper.
        var mapper = applicationMapper.copy();
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxStringLength(MAX_RESPONSE_BYTES).maxNestingDepth(18)
                .maxNameLength(200).maxNumberLength(32).build());
        return mapper;
    }
}
