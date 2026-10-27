package com.notearray.resource;

import com.notearray.entity.LoginChallenge;
import com.notearray.entity.User;
import com.notearray.service.AuthenticationService;
import com.notearray.service.ClientIp;
import com.notearray.service.EmailPolicy;
import com.notearray.service.LoginChallengeService;
import com.notearray.service.LoginThrottleService;
import com.notearray.service.PasswordPolicy;
import com.notearray.service.RecoveryCodeService;
import com.notearray.service.SessionService;
import com.notearray.service.StripeBillingService;
import com.notearray.service.TotpAccountService;
import com.notearray.service.TotpService;
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
import java.util.List;
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

    @Inject
    LoginChallengeService loginChallenges;

    @Inject
    TotpAccountService totpAccounts;

    @Inject
    RecoveryCodeService recoveryCodes;

    @ConfigProperty(name = "notearray.totp.issuer", defaultValue = "NoteArray")
    String totpIssuer;

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

    /** Feature switch for plans/checkout; see BillingConfigResource and application.properties. */
    @ConfigProperty(name = "notearray.billing.enabled", defaultValue = "true")
    boolean billingEnabled;

    /** Request body for create/update. For update, every field is optional.
     *
     * {@code plan} is intentionally accepted-but-rejected (REVIEW finding #26):
     * the server never writes it, so the field cannot self-escalate a plan —
     * plans change exclusively through the paid Stripe checkout flow.
     * */
    public record UserRequest(
            String name,
            String email,
            String password,
            String currentPassword,
            String confirmPassword,
            String themeColor,
            String plan,
            String totpCode,
            String recoveryCode) {
        public UserRequest(String name, String email, String password, String themeColor) {
            this(name, email, password, null, null, themeColor, null, null, null);
        }

        public UserRequest(String name, String email, String password, String currentPassword, String themeColor) {
            this(name, email, password, currentPassword, null, themeColor, null, null, null);
        }

        public UserRequest(
                String name,
                String email,
                String password,
                String currentPassword,
                String confirmPassword,
                String themeColor) {
            this(name, email, password, currentPassword, confirmPassword, themeColor, null, null, null);
        }
    }

    /** Request body for login. */
    public record LoginRequest(String email, String password) {}

    /** Second-factor step after a correct password. */
    public record LoginTotpRequest(String challenge, String code, String recoveryCode) {}

    /** 202 body when the password was accepted but a TOTP code is required. */
    public record LoginTotpRequired(boolean totpRequired, String challenge, long expiresInSeconds) {}

    public record TotpStatusResponse(boolean enabled, Instant enabledAt, long recoveryCodesRemaining) {}

    public record TotpSetupRequest(String currentPassword) {}

    public record TotpSetupResponse(
            String secret, String otpauthUrl, String issuer, String account, int digits, int periodSeconds) {}

    public record TotpCodeRequest(String code, String recoveryCode) {}

    public record TotpEnableResponse(boolean enabled, List<String> recoveryCodes) {}

    public record TotpRecoveryCodesResponse(List<String> recoveryCodes) {}

    public record TotpDisableRequest(String currentPassword, String code, String recoveryCode) {}

    /** Request body for initiating a plan change / Stripe Checkout session. */
    public record PlanCheckoutRequest(String plan) {}

    /** Response returned when initiating a plan change or Stripe Checkout session. */
    public record PlanCheckoutResponse(User user, String checkoutUrl, String status) {}

    /** Request body for synchronizing plan state. The client may only name the
     *  action and the opaque checkout session ID; plan and Stripe identifiers
     *  are resolved server-side from Stripe (REVIEW #26). Since the signed
     *  `/api/stripe/webhook` endpoint (Layer 3) exists, the client can no longer
     *  report billing events at all: `past_due`/`subscription_deleted` were
     *  removed, leaving only payment confirmation and pending-checkout cleanup. */
    public record PlanSyncRequest(String action, String checkoutSessionId) {}

    /** Request body for account deletion. The current password must be re-verified. */
    public record DeleteRequest(String currentPassword, String totpCode, String recoveryCode) {
        public DeleteRequest(String currentPassword) {
            this(currentPassword, null, null);
        }
    }

    /** 400 for attempts to write the plan through user CRUD (REVIEW finding #26). */
    static Response planWriteRejected() {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity("plan cannot be set directly; use /plan/checkout")
                .build();
    }

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

    /** Two-factor status for the signed-in account. */
    @GET
    @Path("/me/totp")
    public Response totpStatus() {
        User user = getAuthenticatedUser();
        if (user == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        return Response.ok(new TotpStatusResponse(
                totpAccounts.isEnabled(user.id),
                totpAccounts.enabledAt(user.id),
                totpAccounts.remainingRecoveryCodes(user.id))).build();
    }

    /**
     * Start (or restart) TOTP enrollment. Requires the current password so a
     * stolen session cannot attach an attacker-controlled authenticator.
     */
    @POST
    @Path("/me/totp/setup")
    public Response totpSetup(TotpSetupRequest req) {
        User user = getAuthenticatedUser();
        if (user == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (req == null || isBlank(req.currentPassword())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Current password is required").build();
        }
        if (!BcryptUtil.matches(req.currentPassword(), user.passwordHash)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Current password is incorrect").build();
        }
        if (totpAccounts.isEnabled(user.id)) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("Two-factor authentication is already enabled").build();
        }
        String secret;
        try {
            secret = totpAccounts.startEnrollment(user.id);
        } catch (IllegalStateException e) {
            return totpUnavailable();
        }
        return Response.ok(new TotpSetupResponse(
                secret,
                TotpService.otpauthUrl(user.email, totpIssuer, secret),
                totpIssuer,
                user.email,
                TotpService.DIGITS,
                TotpService.PERIOD_SECONDS)).build();
    }

    /** Confirm a pending enrollment; recovery codes are returned exactly once. */
    @POST
    @Path("/me/totp/enable")
    public Response totpEnable(TotpCodeRequest req) {
        User user = getAuthenticatedUser();
        if (user == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (totpAccounts.isEnabled(user.id)) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("Two-factor authentication is already enabled").build();
        }
        if (req == null || isBlank(req.code())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("verification code is required").build();
        }
        List<String> codes;
        try {
            codes = totpAccounts.enable(user.id, req.code());
        } catch (IllegalStateException e) {
            return totpUnavailable();
        }
        if (codes == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("invalid verification code or setup was not started").build();
        }
        loginChallenges.deleteForUser(user.id);
        sessions.invalidateOthers(user.id, headers.getCookies().get(SessionService.COOKIE_NAME));
        return Response.ok(new TotpEnableResponse(true, codes)).build();
    }

    /** Disable TOTP: current password plus a TOTP or recovery code. */
    @POST
    @Path("/me/totp/disable")
    public Response totpDisable(TotpDisableRequest req) {
        User user = getAuthenticatedUser();
        if (user == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (!totpAccounts.isEnabled(user.id)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Two-factor authentication is not enabled").build();
        }
        if (req == null || isBlank(req.currentPassword())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Current password is required").build();
        }
        if (!BcryptUtil.matches(req.currentPassword(), user.passwordHash)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Current password is incorrect").build();
        }
        TotpAccountService.Verification verification =
                totpAccounts.verify(user.id, req.code(), req.recoveryCode());
        if (verification == TotpAccountService.Verification.UNAVAILABLE) {
            return totpUnavailable();
        }
        if (verification != TotpAccountService.Verification.VALID) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("invalid verification code").build();
        }
        totpAccounts.disable(user.id);
        loginChallenges.deleteForUser(user.id);
        sessions.invalidateOthers(user.id, headers.getCookies().get(SessionService.COOKIE_NAME));
        return Response.noContent().build();
    }

    /** Replace all recovery codes (old ones stop working immediately). */
    @POST
    @Path("/me/totp/recovery-codes")
    public Response regenerateRecoveryCodes(TotpCodeRequest req) {
        User user = getAuthenticatedUser();
        if (user == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (!totpAccounts.isEnabled(user.id)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Two-factor authentication is not enabled").build();
        }
        if (req == null || (isBlank(req.code()) && isBlank(req.recoveryCode()))) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("verification code is required").build();
        }
        TotpAccountService.Verification verification =
                totpAccounts.verify(user.id, req.code(), req.recoveryCode());
        if (verification == TotpAccountService.Verification.UNAVAILABLE) {
            return totpUnavailable();
        }
        if (verification != TotpAccountService.Verification.VALID) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("invalid verification code").build();
        }
        return Response.ok(new TotpRecoveryCodesResponse(recoveryCodes.replaceCodes(user.id))).build();
    }

    private static Response totpUnavailable() {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity("Two-factor authentication is not configured on this server").build();
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
        User user = authentication.verifyCredentials(normalizedEmail, req.password());
        if (user == null) {
            throttle.recordLoginFailure(normalizedEmail);
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("invalid email or password").build();
        }
        throttle.recordLoginSuccess(normalizedEmail);
        if (totpAccounts.isEnabled(user.id)) {
            LoginChallengeService.IssuedChallenge challenge = loginChallenges.create(user.id);
            return Response.accepted(new LoginTotpRequired(
                    true, challenge.token(), challenge.expiresInSeconds())).build();
        }
        return completeLogin(user);
    }

    /**
     * Second login step: exchange a password-verified challenge plus a TOTP (or
     * recovery) code for a session. No cookie exists until this succeeds, and a
     * challenge is single-use, expires after five minutes and dies after five
     * wrong attempts.
     */
    @POST
    @Path("/login/totp")
    public Response loginTotp(LoginTotpRequest req) {
        if (req == null || isBlank(req.challenge())
                || (isBlank(req.code()) && isBlank(req.recoveryCode()))) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("challenge and verification code are required").build();
        }
        LoginChallenge challenge = loginChallenges.findLive(req.challenge());
        if (challenge == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("invalid or expired verification code").build();
        }
        LoginThrottleService.Decision decision = throttle.startTotpLogin(challenge.userId, clientIp());
        if (!decision.allowed()) {
            return tooManyAttempts(decision.retryAfterSeconds());
        }
        if (decision.waitSeconds() > 0) {
            sleep(decision.waitSeconds() * 1000L);
        }

        User user = User.findById(challenge.userId);
        if (user == null) {
            loginChallenges.consume(challenge.id);
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("invalid or expired verification code").build();
        }

        TotpAccountService.Verification verification =
                totpAccounts.verify(user.id, req.code(), req.recoveryCode());
        if (verification == TotpAccountService.Verification.UNAVAILABLE) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("Two-factor verification is temporarily unavailable. "
                            + "Use a recovery code or contact the operator.").build();
        }
        if (verification != TotpAccountService.Verification.VALID) {
            loginChallenges.recordFailure(challenge.id);
            throttle.recordTotpFailure(user.id);
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("invalid or expired verification code").build();
        }
        loginChallenges.consume(challenge.id);
        throttle.recordTotpSuccess(user.id);
        return completeLogin(user);
    }

    /** Common tail of both login paths: mark the login and issue the session cookie. */
    private Response completeLogin(User user) {
        user.lastLogin = Instant.now(); // detached copy for the response
        authentication.markLoginSuccess(user.id);
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
        if (!isBlank(req.plan())) {
            return planWriteRejected();
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
        // NOTE: req.plan() is never written — see planWriteRejected() (REVIEW #26).
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
        if (!isBlank(req.plan())) {
            return planWriteRejected();
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
            if (totpAccounts.isEnabled(user.id)) {
                TotpAccountService.Verification verification =
                        totpAccounts.verify(user.id, req.totpCode(), req.recoveryCode());
                if (verification == TotpAccountService.Verification.UNAVAILABLE) {
                    return totpUnavailable();
                }
                if (verification != TotpAccountService.Verification.VALID) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity("Two-factor verification code is required or invalid").build();
                }
            }
            user.passwordHash = BcryptUtil.bcryptHash(req.password());
        }
        if (!isBlank(req.themeColor())) {
            user.themeColor = req.themeColor();
        }
        // NOTE: req.plan() is never written — see planWriteRejected() (REVIEW #26).

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
            loginChallenges.deleteForUser(user.id);
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
                stripePremiumCheckoutUrl != null ? stripePremiumCheckoutUrl.orElse(null) : null,
                stripeBilling,
                billingEnabled);
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
        return applyPlanSync(user, req, stripeBilling);
    }

    static Response applyPlanCheckout(
            User user,
            PlanCheckoutRequest req,
            String proCheckoutUrl,
            String premiumCheckoutUrl,
            StripeBillingService billing,
            boolean billingEnabled) {
        if (!billingEnabled) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("Plan upgrades are currently disabled").build();
        }
        if (req == null || isBlank(req.plan())) {
            return Response.status(Response.Status.BAD_REQUEST).entity("plan is required").build();
        }
        String targetPlan = req.plan().trim().toLowerCase();
        if (!PLANS.contains(targetPlan)) {
            return Response.status(Response.Status.BAD_REQUEST).entity("invalid plan").build();
        }
        if ("free".equals(targetPlan)) {
            return downgradeToFree(user, billing);
        }
        // Paid tier ('pro' or 'premium'): record pending Stripe checkout state.
        user.pendingPlan = targetPlan;
        user.planStatus = User.PLAN_STATUS_PENDING_CHECKOUT;
        user.planUpdatedAt = Instant.now();
        String checkoutUrl = "pro".equals(targetPlan) ? proCheckoutUrl : premiumCheckoutUrl;
        return Response.ok(new PlanCheckoutResponse(user, checkoutUrl, user.planStatus)).build();
    }

    /**
     * Downgrade to the free plan. A live Stripe subscription is cancelled first
     * ({@code DELETE /v1/subscriptions/{id}}) and the local plan only moves to
     * free once Stripe confirms it; otherwise the customer would keep being
     * charged for a plan the app no longer shows. On failure the pre-request
     * state is restored so the user stays on their paid plan and can retry.
     */
    static Response downgradeToFree(User user, StripeBillingService billing) {
        String previousStatus = user.planStatus;
        Instant previousUpdatedAt = user.planUpdatedAt;

        boolean cancelled;
        if (billing == null) {
            // No billing service: only safe when there is nothing that could be
            // live in Stripe (free plan and no stored subscription id).
            cancelled = (user.plan == null || "free".equalsIgnoreCase(user.plan.trim()))
                    && isBlank(user.stripeSubscriptionId);
        } else {
            cancelled = billing.awaitSubscriptionCancellation(user);
        }

        if (!cancelled) {
            // The helper flags pending_cancellation before calling Stripe; put
            // the row back exactly as it was so a failed downgrade is a no-op.
            user.planStatus = previousStatus;
            user.planUpdatedAt = previousUpdatedAt;
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity("Could not confirm subscription cancellation with Stripe. "
                            + "Your plan was not changed to Free.")
                    .build();
        }

        user.plan = "free";
        user.planStatus = User.PLAN_STATUS_ACTIVE;
        user.pendingPlan = null;
        user.stripeSubscriptionId = null;
        user.planUpdatedAt = Instant.now();
        return Response.ok(new PlanCheckoutResponse(user, null, user.planStatus)).build();
    }

    static Response applyPlanSync(User user, PlanSyncRequest req, StripeBillingService billing) {
        if (req == null || isBlank(req.action())) {
            return Response.status(Response.Status.BAD_REQUEST).entity("action is required").build();
        }
        String action = req.action().trim().toLowerCase();
        switch (action) {
            case "confirm": {
                // REVIEW #26: the client may only present the opaque checkout
                // session ID. Plan tier and Stripe identifiers are read from
                // Stripe with the server API key — nothing here is trusted from
                // the request body anymore.
                String checkoutSessionId = req.checkoutSessionId() == null
                        ? null
                        : req.checkoutSessionId().trim();
                if (checkoutSessionId == null || checkoutSessionId.isBlank()) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity("checkoutSessionId is required to confirm a payment").build();
                }
                StripeBillingService.VerifiedCheckout verified = billing == null
                        ? null
                        : billing.verifyCheckoutSession(checkoutSessionId);
                if (verified == null || isBlank(verified.plan()) || !PLANS.contains(verified.plan())) {
                    return Response.status(422)
                            .entity("Stripe payment could not be verified; the plan was not activated")
                            .build();
                }
                user.plan = verified.plan();
                user.pendingPlan = null;
                user.planStatus = User.PLAN_STATUS_ACTIVE;
                if (!isBlank(verified.stripeCustomerId())) {
                    user.stripeCustomerId = verified.stripeCustomerId();
                }
                if (!isBlank(verified.stripeSubscriptionId())) {
                    user.stripeSubscriptionId = verified.stripeSubscriptionId();
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
            default:
                // NOTE: past_due / subscription_deleted are webhook-only now (signed
                // StripeWebhookResource); clients cannot report billing state.
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("invalid sync action").build();
        }
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") UUID id, DeleteRequest req) {
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
        // Destructive deletion requires the current password, mirroring the
        // password-change flow: a stolen or shared session alone can no longer
        // irreversibly destroy the account and all of its notes/attachments.
        Response passwordError = validateDeleteConfirmation(target, req);
        if (passwordError != null) {
            return passwordError;
        }
        if (totpAccounts.isEnabled(target.id)) {
            TotpAccountService.Verification verification =
                    totpAccounts.verify(target.id, req.totpCode(), req.recoveryCode());
            if (verification == TotpAccountService.Verification.UNAVAILABLE) {
                return totpUnavailable();
            }
            if (verification != TotpAccountService.Verification.VALID) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("Two-factor verification code is required or invalid").build();
            }
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

    /**
     * Validate the password re-verification for account deletion: the current
     * password must be supplied and match the stored hash. Runs before any
     * Stripe interaction so a wrong password never triggers billing calls.
     * Returns a 400 response on failure, or {@code null} when confirmed.
     */
    static Response validateDeleteConfirmation(User target, DeleteRequest req) {
        String currentPassword = req == null ? null : req.currentPassword();
        if (isBlank(currentPassword)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Password is required to delete your account").build();
        }
        if (!BcryptUtil.matches(currentPassword, target.passwordHash)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Current password is incorrect").build();
        }
        return null;
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
