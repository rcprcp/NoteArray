package com.notearray.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.notearray.entity.User;
import com.notearray.resource.UserResource.UserRequest;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

class UserDateFormatTest {

    private static final String[] PRESETS = {
            "adaptive", "day_month", "month_day", "mdy_slash",
            "dmy_slash", "iso", "relative", "full",
    };

    @Test
    void acceptsEveryPresetOfferedByTheUi() {
        for (String preset : PRESETS) {
            assertNull(UserResource.validateDateFormat(preset));
        }
    }

    @Test
    void blankMeansLeaveUnchanged() {
        assertNull(UserResource.validateDateFormat(null));
        assertNull(UserResource.validateDateFormat(""));
        assertNull(UserResource.validateDateFormat("   "));
    }

    @Test
    void rejectsUnknownPreset() {
        Response res = UserResource.validateDateFormat("european");
        assertEquals(400, res.getStatus());
        assertEquals("invalid dateFormat", res.getEntity());
    }

    @Test
    void newUsersDefaultToAdaptive() {
        assertEquals("adaptive", new User().dateFormat);
        assertEquals(User.DEFAULT_DATE_FORMAT, new User().dateFormat);
    }

    @Test
    void updateRequestCarriesDateFormat() {
        UserRequest req = new UserRequest(
                null, null, null, null, null, null, null, null, null, null, "iso");
        assertEquals("iso", req.dateFormat());
    }
}
