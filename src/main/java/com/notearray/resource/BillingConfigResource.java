package com.notearray.resource;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Public, side-effect-free client configuration.
 *
 * <p>Today this exposes only the billing feature switch
 * ({@code notearray.billing.enabled}). The UI uses it to decide whether to show
 * the "Available Plans" card; it deliberately contains no secrets, so it can be
 * fetched before login and without a session.
 */
@Path("/api/billing")
@Produces(MediaType.APPLICATION_JSON)
public class BillingConfigResource {

    @ConfigProperty(name = "notearray.billing.enabled", defaultValue = "true")
    boolean billingEnabled;

    @GET
    @Path("/config")
    public Response config() {
        return Response.ok(Map.of("enabled", billingEnabled)).build();
    }
}
