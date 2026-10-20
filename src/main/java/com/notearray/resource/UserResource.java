package com.notearray.resource;

import com.notearray.entity.User;
import com.notearray.service.AuthenticationService;
import com.notearray.service.ClientIp;
import com.notearray.service.EmailPolicy;
import com.notearray.service.LoginThrottleService;
import com.notearray.service.PasswordPolicy;
import com.notearray.service.SessionService;
import com.notearray.service.StripeBillingService;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.vertx.core.http.HttpServerRequest;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/api/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserResource {

    /**
     * Mantine color palettes selectable in the UI. Must stay in sync with
     * THEME_COLORS in ui/src/ThemeButton.jsx (client-side copy).
     */
    private static final Set<String> THEME_COLORS = Set.of(
            "dark", "gray", "red", "pink", "grape", "violet", "indigo", "blue",
            "cyan", "teal", "green", "lime", "yellow", "orange");

    private static final Set<String> PLANS = Set.of("free", "pro", "premium");

    @Context
    HttpHeaders headers;

    /** Peer HTTP request, used only to key rate limits on the client address. */
    @Context
    HttpServerRequest request;

    @Inject
    SessionService sessions;

    @Inject
    AuthenticationService authentication;

    @Inject
    LoginThrottleService throttle;

    @Inject
    StripeBillingService stripeBilling;

    /**
     * Trust the X-Forwarded-For header when resolving the client IP. Enable only
     * behind a reverse proxy that overwrites the header (see {@link ClientIp}).
     */
    @ConfigProperty(name = "notearray.rate-limit.trust-proxy", defaultValue = "false")
    boolean trustProxy;

    @ConfigProperty(name = "notearray.stripe.pro-checkout-url")
    Optional<String> stripeProCheckoutUrl;

    @ConfigProperty(name = "notearray.stripe.premium-checkout-url")
    Optional<String> stripePremiumCheckoutUrl;

    /** Request body for create/update. For update, every field is optional. */
    public record UserRequest(
            String name,
            String email,
            String password,
            String currentPassword,
            String confirmPassword,
            String themeColor,
            String plan) {
        public UserRequest(String name, String email, String password, String themeColor) {
            this(name, email, password, null, null, themeColor, null);
        }

        public UserRequest(String name, String email, String password, String currentPassword, String themeColor) {
            this(name, email, password, currentPassword, null, themeColor, null);
        }

        public UserRequest(
                String name,
                String email,
                String password,
                String currentPassword,
                String confirmPassword,
                String themeColor) {
            this(name, email, password, currentPassword, confirmPassword, themeColor, null);
        }
    }

    /** Request body for login. */
    public record LoginRequest(String email, String password) {}

    /** Request body for initiating a plan change / Stripe Checkout session. */
    public record PlanCheckoutRequest(String plan) {}

    /** Response returned when initiating a plan change or Stripe Checkout session. */
    public record PlanCheckoutResponse(User user, String checkoutUrl, String status) {}

    /** Request body for synchronizing plan state after Stripe Checkout or webhook events. */
    public record PlanSyncRequest(
            String action,
            String plan,
            String stripeCustomerId,
            String stripeSubscriptionId) {}

    /** Get the authenticated user from the session cookie. Returns null if not logged in. */
    private User getAuthenticatedUser() {
        return sessions.resolve(headers.getCookies().get(SessionService.COOKIE_NAME));
    }

    /**
     * Check if caller has permission to access a target user.
     * Superusers can access anyone; regular users can only access themselves.
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

    /**
     * Log in with email + password.
     *
     * <p>Runs outside a transaction so the throttling delay (which can hold the
     * request for up to {@link LoginThrottleService#MAX_SERVER_SIDE_WAIT}) never
     * ties up a database connection. Credential checking and session creation run
     * in their own short transactions afterwards.
     */
    @POST
    @Path("/login")
    public Response login(LoginRequest req) {
        if (req == null || isBlank(req.email()) || isBlank(req.password())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("email and password are required").build();
        }
        String normalizedEmail = EmailPolicy.normalizeEmail(req.email());
        String emailError = EmailPolicy.validateEmail(normalizedEmail);
        if (emailError != null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(emailError).build();
        }
        LoginThrottleService.Decision decision = throttle.startLogin(normalizedEmail, clientIp());
        if (!decision.allowed()) {
            return tooManyAttempts(decision.retryAfterSeconds());
        }
        if (decision.waitSeconds() > 0) {
            sleep(decision.waitSeconds() * 1000L);
        }
        User user = authentication.authenticate(normalizedEmail, req.password());
        if (user == null) {
            throttle.recordLoginFailure(normalizedEmail);
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("invalid email or password").build();
        }
        throttle.recordLoginSuccess(normalizedEmail);
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
        LoginThrottleService.Decision decision = throttle.startSignup(clientIp());
        if (!decision.allowed()) {
            return tooManyAttempts(decision.retryAfterSeconds());
        }
        String normalizedName = EmailPolicy.normalizeName(req.name());
        String nameError = EmailPolicy.validateName(normalizedName);
        if (nameError != null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(nameError).build();
        }
        String normalizedEmail = EmailPolicy.normalizeEmail(req.email());
        String emailError = EmailPolicy.validateEmail(normalizedEmail);
        if (emailError != null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(emailError).build();
        }
        if (!isBlank(req.themeColor()) && !THEME_COLORS.contains(req.themeColor())) {
            return Response.status(Response.Status.BAD_REQUEST).entity("invalid themeColor").build();
        }
        if (!isBlank(req.plan()) && !PLANS.contains(req.plan().trim().toLowerCase())) {
            return Response.status(Response.Status.BAD_REQUEST).entity("invalid plan").build();
        }
        if (User.findByName(normalizedName) != null) {
            return Response.status(Response.Status.CONFLICT).entity("name already in use").build();
        }
        if (User.findByEmail(normalizedEmail) != null) {
            return Response.status(Response.Status.CONFLICT).entity("email already in use").build();
        }
        Response passwordError = invalidPassword(req.password());
        if (passwordError != null) {
            return passwordError;
        }
        User user = new User();
        user.name = normalizedName;
        user.email = normalizedEmail;
        user.passwordHash = BcryptUtil.bcryptHash(req.password());
        if (!isBlank(req.themeColor())) {
            user.themeColor = req.themeColor();
        }
        if (!isBlank(req.plan())) {
            user.plan = req.plan().trim().toLowerCase();
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
        if (!isBlank(req.plan()) && !PLANS.contains(req.plan().trim().toLowerCase())) {
            return Response.status(Response.Status.BAD_REQUEST).entity("invalid plan").build();
        }
        if (!isBlank(req.name())) {
            String normalizedName = EmailPolicy.normalizeName(req.name());
            String nameError = EmailPolicy.validateName(normalizedName);
            if (nameError != null) {
                return Response.status(Response.Status.BAD_REQUEST).entity(nameError).build();
            }
            if (!normalizedName.equals(user.name)) {
                if (User.findByName(normalizedName) != null) {
                    return Response.status(Response.Status.CONFLICT).entity("name already in use").build();
                }
                user.name = normalizedName;
            }
        }
        if (!isBlank(req.email())) {
            String normalizedEmail = EmailPolicy.normalizeEmail(req.email());
            String emailError = EmailPolicy.validateEmail(normalizedEmail);
            if (emailError != null) {
                return Response.status(Response.Status.BAD_REQUEST).entity(emailError).build();
            }
            if (!normalizedEmail.equalsIgnoreCase(user.email)) {
                if (User.findByEmail(normalizedEmail) != null) {
                    return Response.status(Response.Status.CONFLICT).entity("email already in use").build();
                }
            }
            user.email = normalizedEmail;
        }
        boolean passwordChanged = !isBlank(req.password())
                || !isBlank(req.currentPassword())
                || !isBlank(req.confirmPassword());
        if (passwordChanged) {
            Response passwordError = validatePasswordChange(user, req);
            if (passwordError != null) {
                return passwordError;
            }
            user.passwordHash = BcryptUtil.bcryptHash(req.password());
        }
        if (!isBlank(req.themeColor())) {
            user.themeColor = req.themeColor();
        }
        if (!isBlank(req.plan())) {
            user.plan = req.plan().trim().toLowerCase();
        }

        try {
            user.flush();
        } catch (PersistenceException e) {
            // Unique-constraint race on name/email; map to a 409 rather than a 500.
            return Response.status(Response.Status.CONFLICT).entity("name or email already in use").build();
        }

        Response.ResponseBuilder response = Response.ok(user);
        if (passwordChanged) {
            // A password change invalidates every existing session for the user
            // and signs out the caller so they must log in again with the new password.
            sessions.invalidateAllForUser(user.id);
            if (caller.id.equals(user.id)) {
                response.header("Set-Cookie", sessions.expiredCookieHeader());
            }
        }
        return response.build();
    }

    @POST
    @Path("/{id}/plan/checkout")
    @Transactional
    public Response checkoutPlan(@PathParam("id") UUID id, PlanCheckoutRequest req) {
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
        return applyPlanCheckout(
                user,
                req,
                stripeProCheckoutUrl != null ? stripeProCheckoutUrl.orElse(null) : null,
                stripePremiumCheckoutUrl != null ? stripePremiumCheckoutUrl.orElse(null) : null);
    }

    @POST
    @Path("/{id}/plan/sync")
    @Transactional
    public Response syncPlan(@PathParam("id") UUID id, PlanSyncRequest req) {
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
        return applyPlanSync(user, req);
    }

    static Response applyPlanCheckout(
            User user,
            PlanCheckoutRequest req,
            String proCheckoutUrl,
            String premiumCheckoutUrl) {
        if (req == null || isBlank(req.plan())) {
            return Response.status(Response.Status.BAD_REQUEST).entity("plan is required").build();
        }
        String targetPlan = req.plan().trim().toLowerCase();
        if (!PLANS.contains(targetPlan)) {
            return Response.status(Response.Status.BAD_REQUEST).entity("invalid plan").build();
        }
        if ("free".equals(targetPlan)) {
            user.plan = "free";
            user.planStatus = User.PLAN_STATUS_ACTIVE;
            user.pendingPlan = null;
            user.stripeSubscriptionId = null;
            user.planUpdatedAt = Instant.now();
            return Response.ok(new PlanCheckoutResponse(user, null, user.planStatus)).build();
        }
        // Paid tier ('pro' or 'premium'): record pending Stripe checkout state.
        user.pendingPlan = targetPlan;
        user.planStatus = User.PLAN_STATUS_PENDING_CHECKOUT;
        user.planUpdatedAt = Instant.now();
        String checkoutUrl = "pro".equals(targetPlan) ? proCheckoutUrl : premiumCheckoutUrl;
        return Response.ok(new PlanCheckoutResponse(user, checkoutUrl, user.planStatus)).build();
    }

    static Response applyPlanSync(User user, PlanSyncRequest req) {
        if (req == null || isBlank(req.action())) {
            return Response.status(Response.Status.BAD_REQUEST).entity("action is required").build();
        }
        String action = req.action().trim().toLowerCase();
        switch (action) {
            case "confirm": {
                String resolvedPlan = !isBlank(req.plan())
                        ? req.plan().trim().toLowerCase()
                        : user.pendingPlan;
                if (isBlank(resolvedPlan) || !PLANS.contains(resolvedPlan)) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity("valid target plan is required to confirm").build();
                }
                user.plan = resolvedPlan;
                user.pendingPlan = null;
                user.planStatus = User.PLAN_STATUS_ACTIVE;
                if (!isBlank(req.stripeCustomerId())) {
                    user.stripeCustomerId = req.stripeCustomerId().trim();
                }
                if (!isBlank(req.stripeSubscriptionId())) {
                    user.stripeSubscriptionId = req.stripeSubscriptionId().trim();
                }
                user.planUpdatedAt = Instant.now();
                return Response.ok(user).build();
            }
            case "cancel": {
                user.pendingPlan = null;
                user.planStatus = User.PLAN_STATUS_ACTIVE;
                user.planUpdatedAt = Instant.now();
                return Response.ok(user).build();
            }
            case "past_due": {
                user.planStatus = User.PLAN_STATUS_PAST_DUE;
                user.planUpdatedAt = Instant.now();
                return Response.ok(user).build();
            }
            case "subscription_deleted": {
                user.plan = "free";
                user.pendingPlan = null;
                user.planStatus = User.PLAN_STATUS_CANCELED;
                user.stripeSubscriptionId = null;
                user.planUpdatedAt = Instant.now();
                return Response.ok(user).build();
            }
            default:
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("invalid sync action").build();
        }
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
        User target = User.findById(id);
        if (target == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        Response deleteError = cancelSubscriptionAndValidateDeletion(target, stripeBilling);
        if (deleteError != null) {
            return deleteError;
        }
        sessions.invalidateAllForUser(target.id);
        target.delete();
        return Response.noContent()
                .header("Set-Cookie", sessions.expiredCookieHeader())
                .build();
    }

    static Response cancelSubscriptionAndValidateDeletion(User target, StripeBillingService billing) {
        if (target == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        boolean pendingUpgrade = User.PLAN_STATUS_PENDING_CHECKOUT.equalsIgnoreCase(target.planStatus)
                || !isBlank(target.pendingPlan);
        if (pendingUpgrade) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Please cancel or complete your pending Stripe checkout before deleting your account.")
                    .build();
        }
        String plan = target.plan != null ? target.plan.trim().toLowerCase() : "free";
        if (!"free".equals(plan) || !isBlank(target.stripeSubscriptionId)) {
            StripeBillingService service = billing != null ? billing : new StripeBillingService();
            boolean confirmed = service.awaitSubscriptionCancellation(target);
            if (!confirmed) {
                return Response.status(Response.Status.BAD_GATEWAY)
                        .entity("Could not confirm subscription cancellation with Stripe. "
                                + "Your account was not deleted to prevent recurring charges.")
                        .build();
            }
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Client address used to key IP-based limits (see {@link ClientIp}). */
    private String clientIp() {
        return ClientIp.resolve(request, headers, trustProxy);
    }

    /** 429 telling the client how long to wait — used by both the login and signup budgets. */
    private static Response tooManyAttempts(long retryAfterSeconds) {
        return Response.status(429)
                .header("Retry-After", Long.toString(retryAfterSeconds))
                .entity("too many attempts; try again in " + retryAfterSeconds + " seconds")
                .build();
    }

    /** Wait out a throttling delay; the login endpoint is blocking, so sleeping a worker thread is safe. */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Validate a password-change request: all fields must be present, the current
     * password is verified against the stored hash first, and only then is the new
     * password validated against {@link PasswordPolicy} and checked against any
     * confirmation field.
     */
    static Response validatePasswordChange(User user, UserRequest req) {
        if (isBlank(req.currentPassword())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Current password is required").build();
        }
        if (isBlank(req.password())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("New password is required").build();
        }
        if (req.confirmPassword() != null && isBlank(req.confirmPassword())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Password verification is required").build();
        }
        if (!BcryptUtil.matches(req.currentPassword(), user.passwordHash)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Current password is incorrect").build();
        }
        Response passwordError = invalidPassword(req.password());
        if (passwordError != null) {
            return passwordError;
        }
        if (req.confirmPassword() != null && !req.password().equals(req.confirmPassword())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Passwords do not match").build();
        }
        return null;
    }

    /**
     * 400 listing every unmet {@link PasswordPolicy} rule, or null when the
     * password is acceptable. Applied wherever a password can be set (create and
     * update), so the API is authoritative regardless of client checks.
     */
    private static Response invalidPassword(String password) {
        java.util.List<String> failures = PasswordPolicy.validate(password);
        if (failures.isEmpty()) {
            return null;
        }
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(String.join("; ", failures)).build();
    }
}
