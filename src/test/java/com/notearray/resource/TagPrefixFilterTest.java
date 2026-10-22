package com.notearray.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tag filtering on the list endpoint: LIKE escaping and the hierarchical
 * prefix condition (token itself OR any deeper token).
 */
class TagPrefixFilterTest {

    @Test
    void lowercaseIsKeptAndWildcardsAreEscaped() {
        assertEquals("grq\\_cob\\%ol\\\\x", NoteResource.escapeLike(" GrQ_cob%ol\\x "));
    }

    @Test
    void prefixConditionAddsTokenAndSubtreePatterns() {
        StringBuilder sql = new StringBuilder("SELECT 1");
        Map<String, Object> params = new HashMap<>();
        NoteResource.appendTagPrefixCondition(sql, params, NoteResource.escapeLike("grq"));
        String statement = sql.toString();
        // Both alternatives in one AND group, each the padded-token LIKE form.
        assertTrue(statement.contains("LIKE :prefix0"), statement);
        assertTrue(statement.contains("LIKE :prefix1"), statement);
        assertEquals("% grq %", params.get("prefix0"));
        assertEquals("% grq/% %", params.get("prefix1"));
    }

    @Test
    void underscoresInFolderNamesDoNotExpandToWildcards() {
        Map<String, Object> params = new HashMap<>();
        NoteResource.appendTagPrefixCondition(new StringBuilder(), params, NoteResource.escapeLike("grq_sub"));
        assertEquals("% grq\\_sub %", params.get("prefix0"));
        assertEquals("% grq\\_sub/% %", params.get("prefix1"));
    }

    @Test
    void repeatedCallsGetUniqueParameterNames() {
        StringBuilder sql = new StringBuilder();
        Map<String, Object> params = new HashMap<>();
        NoteResource.appendTagPrefixCondition(sql, params, NoteResource.escapeLike("grq"));
        NoteResource.appendTagPrefixCondition(sql, params, NoteResource.escapeLike("k8s"));
        assertEquals(4, params.size());
        assertEquals("% k8s %", params.get("prefix2"));
        assertEquals("% k8s/% %", params.get("prefix3"));
    }
}
