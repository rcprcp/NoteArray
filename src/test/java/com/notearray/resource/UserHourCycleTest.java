package com.notearray.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.notearray.entity.User;
import com.notearray.resource.UserResource.UserRequest;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

class UserHourCycleTest {

    @Test
    void acceptsSupportedHourCycles() {
        assertNull(UserResource.validateHourCycle("auto"));
        assertNull(UserResource.validateHourCycle("h12"));
        assertNull(UserResource.validateHourCycle("h23"));
    }

    @Test
    void blankMeansLeaveUnchanged() {
        assertNull(UserResource.validateHourCycle(null));
        assertNull(UserResource.validateHourCycle(""));
        assertNull(UserResource.validateHourCycle("   "));
    }

    @Test
    void rejectsUnknownHourCycle() {
        Response res = UserResource.validateHourCycle("military");
        assertEquals(400, res.getStatus());
        assertEquals("invalid hourCycle", res.getEntity());
    }

    @Test
    void newUsersDefaultToTheLocaleClock() {
        assertEquals("auto", new User().hourCycle);
        assertEquals(User.DEFAULT_HOUR_CYCLE, new User().hourCycle);
    }

    @Test
    void updateRequestCarriesHourCycle() {
        UserRequest req = new UserRequest(
                null, null, null, null, null, null, null, null, null, "h23", null);
        assertEquals("h23", req.hourCycle());
    }
}
