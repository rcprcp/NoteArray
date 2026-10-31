package com.notearray.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notearray.entity.User;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Paged note listing: cursor round-trips, query ownership, search validation. */
class NotePaginationTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void cursorRoundTripsThroughBase64() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        NoteResource.NoteCursor original = new NoteResource.NoteCursor(
                1, "title", "asc", "my note", USER_ID);

        String encoded = NoteResource.encodeCursor(mapper, original);
        NoteResource.NoteCursor decoded = NoteResource.decodeCursor(mapper, encoded);

        assertEquals(original, decoded);
        assertTrue(encoded.matches("[A-Za-z0-9_-]+"), encoded);
    }

    @Test
    void malformedCursorIsRejected() {
        ObjectMapper mapper = new ObjectMapper();
        assertThrows(IOException.class, () -> NoteResource.decodeCursor(mapper, "not-base64!!"));
        assertThrows(IOException.class, () -> NoteResource.decodeCursor(mapper, "e30"));
    }

    @Test
    void ownerFilterIsAlwaysAppliedForRegularUsers() {
        User user = new User();
        user.id = USER_ID;
        user.superuser = false;

        NoteResource.NoteListQuery query = NoteResource.buildNoteListQuery(
                user, null, false, false, false,
                List.of(), List.of(), false, false, false,
                null, null, null, null);

        assertTrue(query.sql().toString().contains("n.owner_id = :owner"));
        assertEquals(USER_ID, query.params().get("owner"));
        assertFalse(query.sql().toString().contains("ORDER BY"));
    }

    @Test
    void superuserQueryHasNoOwnerFilter() {
        User user = new User();
        user.id = USER_ID;
        user.superuser = true;

        NoteResource.NoteListQuery query = NoteResource.buildNoteListQuery(
                user, null, false, false, false,
                List.of(), List.of(), false, false, false,
                null, null, null, null);

        assertFalse(query.sql().toString().contains("n.owner_id"));
        assertNull(query.params().get("owner"));
    }

    @Test
    void searchRequiresAtLeastOneScope() {
        assertEquals("Select at least one of searchTitles, searchContent or searchTags",
                NoteResource.validateSearchScopes("work", false, false, false));
        assertNull(NoteResource.validateSearchScopes("work", true, false, false));
        assertNull(NoteResource.validateSearchScopes(null, false, false, false));
    }
}
