package com.notearray.resource;

import com.notearray.entity.User;
import com.notearray.service.SessionService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Handles client-side SPA routes so browser navigation or refreshes on paths
 * like {@code /overview}, {@code /login}, {@code /notes}, or {@code /settings}
 * do not fall through to the default Quarkus 404 page.
 */
@Path("/")
public class SpaResource {

    private static final String INDEX_RESOURCE = "META-INF/resources/index.html";

    @Context
    HttpHeaders headers;

    @Inject
    SessionService sessions;

    private User getAuthenticatedUser() {
        if (headers == null || headers.getCookies() == null || sessions == null) {
            return null;
        }
        return sessions.resolve(headers.getCookies().get(SessionService.COOKIE_NAME));
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response indexPage() {
        return serveIndexHtml();
    }

    @GET
    @Path("/login")
    @Produces(MediaType.TEXT_HTML)
    public Response loginPage() {
        return serveIndexHtml();
    }

    @GET
    @Path("/{path: (?!api(?:/|$)|q(?:/|$)|assets(?:/|$))[^.]+}")
    @Produces(MediaType.TEXT_HTML)
    public Response route(@PathParam("path") String path) {
        User user = getAuthenticatedUser();
        if (user == null) {
            return Response.seeOther(URI.create("/login")).build();
        }
        if ("notes".equals(path)
                || "settings".equals(path)
                || "help/faq".equals(path)
                || "help/user-guide".equals(path)
                || "help/privacy".equals(path)
                || "help/security".equals(path)) {
            return serveIndexHtml();
        }
        return Response.seeOther(URI.create("/notes")).build();
    }

    private Response serveIndexHtml() {
        try (InputStream in = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(INDEX_RESOURCE)) {
            if (in == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Response.ok(html, MediaType.TEXT_HTML_TYPE).build();
        } catch (IOException e) {
            return Response.serverError().build();
        }
    }
}
