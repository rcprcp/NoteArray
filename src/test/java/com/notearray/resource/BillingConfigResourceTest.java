package com.notearray.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BillingConfigResourceTest {

    @Test
    void exposesTheBillingSwitchAsJson() {
        BillingConfigResource resource = new BillingConfigResource();

        resource.billingEnabled = true;
        Response enabled = resource.config();
        assertEquals(200, enabled.getStatus());
        assertEquals(Map.of("enabled", true), enabled.getEntity());

        resource.billingEnabled = false;
        Response disabled = resource.config();
        assertEquals(200, disabled.getStatus());
        assertEquals(Map.of("enabled", false), disabled.getEntity());
    }
}
