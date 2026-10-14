package dev.regresslab.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;

final class StoredJson {
    private StoredJson() {}
    static ObjectMapper reader(ObjectMapper applicationMapper) {
        // PostgreSQL jsonb prints exponent notation as decimal expansion. A finite
        // IEEE double may need over 320 characters, despite a short input literal.
        // This copy is for stored summaries only; MVC/artifact limits stay strict.
        var reader = applicationMapper.copy();
        reader.getFactory().setStreamReadConstraints(applicationMapper.getFactory()
                .streamReadConstraints().rebuild().maxNumberLength(400).build());
        return reader;
    }
}
