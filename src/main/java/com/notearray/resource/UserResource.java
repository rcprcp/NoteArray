package com.notearray.resource;

import com.notearray.entity.Note;
import com.notearray.entity.User;
import com.notearray.service.SessionService;
import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Path("/api/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserResource {

    /** Mantine color palettes selectable in the UI. */
    private static final Set<String> THEME_COLORS = Set.of(
            "dark", "gray", "red", "pink", "grape", "violet", "indigo", "blue",
            "cyan", "teal", "green", "lime", "yellow", "orange");

    @Context
    HttpHeaders headers;

    @Inject
    SessionService sessions;

    /** Request body for create/update. For update, every field is optional. */
    public record UserRequest(String name, String email, String password, String themeColor) {}

    /** Request body for login. */
    public record LoginRequest(String email, String password) {}

    /** Get the authenticated user from the session cookie. Returns null if not logged in. */
    private User getAuthenticatedUser() {
        return sessions.resolve(headers.getCookies().get(SessionService.COOKIE_NAME));
    }

    /**
     * Check if caller has permission to access a target user.
     * Superusers can access anyone; regular users can only access themselves.
     * Returns false and logs access denial if permission is denied.
     */
    private boolean canAccessUser(User caller, UUID targetId) {
        return caller.superuser || caller.id.equals(targetId);
    }

    @GET
    @Path("/me")
    public Response getCurrentUser() {
        User user = getAuthenticatedUser();
        return user == null ? Response.status(Response.Status.UNAUTHORIZED).build() : Response.ok(user).build();
    }

    @POST
    @Path("/login")
    @Transactional
    public Response login(LoginRequest req) {
        if (req == null || isBlank(req.email()) || isBlank(req.password())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("email and password are required").build();
        }
        User user = User.findByEmail(req.email());
        if (user == null || !BcryptUtil.matches(req.password(), user.passwordHash)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("invalid email or password").build();
        }
        user.lastLogin = Instant.now();
        String token = sessions.create(user);
        return Response.ok(user)
                .header("Set-Cookie", sessions.cookieHeader(token))
                .build();
    }

    @POST
    @Path("/logout")
    public Response logout() {
        sessions.invalidate(headers.getCookies().get(SessionService.COOKIE_NAME));
        return Response.noContent()
                .header("Set-Cookie", sessions.expiredCookieHeader())
                .build();
    }

    @POST
    @Transactional
    public Response create(UserRequest req) {
        if (req == null || isBlank(req.name()) || isBlank(req.email()) || isBlank(req.password())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("name, email and password are required").build();
        }
        if (!isBlank(req.themeColor()) && !THEME_COLORS.contains(req.themeColor())) {
            return Response.status(Response.Status.BAD_REQUEST).entity("invalid themeColor").build();
        }
        if (User.findByName(req.name()) != null) {
            return Response.status(Response.Status.CONFLICT).entity("name already in use").build();
        }
        if (User.findByEmail(req.email()) != null) {
            return Response.status(Response.Status.CONFLICT).entity("email already in use").build();
        }
        User user = new User();
        user.name = req.name();
        user.email = req.email();
        user.passwordHash = BcryptUtil.bcryptHash(req.password());
        if (!isBlank(req.themeColor())) {
            user.themeColor = req.themeColor();
        }
        user.persist();
        try {
            user.flush();
        } catch (PersistenceException e) {
            // Unique-constraint race: another request inserted the same name/email
            // after our check above. Map to a 409 rather than a 500.
            return Response.status(Response.Status.CONFLICT).entity("name or email already in use").build();
        }
        return Response.created(URI.create("/api/users/" + user.id)).entity(user).build();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") UUID id) {
        User caller = getAuthenticatedUser();
        if (caller == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (!canAccessUser(caller, id)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        User user = User.findById(id);
        return user == null ? Response.status(Response.Status.NOT_FOUND).build() : Response.ok(user).build();
    }

    @GET
    public Response list() {
        User caller = getAuthenticatedUser();
        if (caller == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (!caller.superuser) {
            // Non-superusers can only list their own user (not useful, but consistent)
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        return Response.ok(User.listAll()).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Response update(@PathParam("id") UUID id, UserRequest req) {
        User caller = getAuthenticatedUser();
        if (caller == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (!canAccessUser(caller, id)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        User user = User.findById(id);
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (req == null) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        if (!isBlank(req.themeColor()) && !THEME_COLORS.contains(req.themeColor())) {
            return Response.status(Response.Status.BAD_REQUEST).entity("invalid themeColor").build();
        }
        if (!isBlank(req.name()) && !req.name().equals(user.name)) {
            if (User.findByName(req.name()) != null) {
                return Response.status(Response.Status.CONFLICT).entity("name already in use").build();
            }
            user.name = req.name();
        }
        if (!isBlank(req.email()) && !req.email().equals(user.email)) {
            if (User.findByEmail(req.email()) != null) {
                return Response.status(Response.Status.CONFLICT).entity("email already in use").build();
            }
            user.email = req.email();
        }
        boolean passwordChanged = !isBlank(req.password());
        if (passwordChanged) {
            user.passwordHash = BcryptUtil.bcryptHash(req.password());
        }
        if (!isBlank(req.themeColor())) {
            user.themeColor = req.themeColor();
        }

        try {
            user.flush();
        } catch (PersistenceException e) {
            // Unique-constraint race on name/email; map to a 409 rather than a 500.
            return Response.status(Response.Status.CONFLICT).entity("name or email already in use").build();
        }

        Response.ResponseBuilder response = Response.ok(user);
        if (passwordChanged) {
            // A password change invalidates every existing session for the user.
            sessions.invalidateAllForUser(user.id);
            if (caller.id.equals(user.id)) {
                // Keep the caller signed in on this device with a brand-new token.
                String token = sessions.create(user);
                response.header("Set-Cookie", sessions.cookieHeader(token));
            }
        }
        return response.build();
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") UUID id) {
        User caller = getAuthenticatedUser();
        if (caller == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (!canAccessUser(caller, id)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return User.deleteById(id)
                ? Response.noContent().build()
                : Response.status(Response.Status.NOT_FOUND).build();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
