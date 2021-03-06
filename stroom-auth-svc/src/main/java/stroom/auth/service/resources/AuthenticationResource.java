package stroom.auth.service.resources;

import com.codahale.metrics.annotation.Timed;
import com.google.common.base.Strings;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Result;
import org.jooq.Table;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import stroom.auth.service.Config;
import stroom.auth.service.TokenGenerator;
import stroom.auth.service.security.CertificateUtil;
import stroom.db.auth.tables.records.UsersRecord;

import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.ZonedDateTime;
import java.util.regex.Pattern;

import static stroom.db.auth.Tables.USERS;

@Path("/authentication/")
@Produces(MediaType.APPLICATION_JSON)
public final class AuthenticationResource {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthenticationResource.class);

    private TokenGenerator tokenGenerator;
    private Config config;
    private final Pattern dnPattern;

    public AuthenticationResource(@NotNull TokenGenerator tokenGenerator, @NotNull Config config) {
        this.tokenGenerator = tokenGenerator;
        this.config = config;
        this.dnPattern = Pattern.compile(config.getCertificateDnPattern());
    }

    @GET
    @Path("/")
    @Consumes({"application/json"})
    @Produces({"application/json"})
    @Timed
    @NotNull
    public final Response welcome() {
        return Response.status(Status.OK).entity("Welcome to the authentication service").build();
    }

    @GET
    @Path("/checkCertificate")
    @Timed
    @NotNull
    public final Response checkCertificate(@Context @NotNull HttpServletRequest httpServletRequest, @Context @NotNull DSLContext database) throws URISyntaxException {
        String certificateDn = CertificateUtil.extractCertificateDN(httpServletRequest);
        Response response;
        if(certificateDn == null) {
            this.LOGGER.debug("No certificate in request. Redirecting to login.");
            response = this.redirectToLoginResponse();
        } else {
            this.LOGGER.debug("Found certificate in request. DN: {}", certificateDn);
            String certificateUsername = CertificateUtil.extractUserIdFromDN(certificateDn, this.dnPattern);
            if(certificateUsername == null) {
                this.LOGGER.debug("Cannot extract user from certificate. Redirecting to login.");
                response = this.redirectToLoginResponse();
            }
        }

        return Response.status(Status.INTERNAL_SERVER_ERROR).build();
    }

    @POST
    @Path("/login")
    @Consumes({"application/json"})
    @Produces({"application/json"})
    @Timed
    @NotNull
    public final Response authenticateAndReturnToken(
            @Context @NotNull DSLContext database, @Nullable Credentials credentials) {
        Response response;
        if(credentials == null
                || Strings.isNullOrEmpty(credentials.getEmail())
                || Strings.isNullOrEmpty(credentials.getPassword())) {
            return Response.status(Status.BAD_REQUEST).entity("Please provide both email and password").build();
        }

        Result results = database
                .selectFrom((Table) USERS)
                .where(USERS.EMAIL.eq(credentials.getEmail()))
                .fetch();

        if(results.size() != 1) {
            this.LOGGER.debug("Request to log in with invalid email: " + credentials.getEmail());
            return unauthorisedResponse();
        }

        UsersRecord user = (UsersRecord) database
                .selectFrom((Table) USERS)
                .where(new Condition[]{USERS.EMAIL.eq(credentials.getEmail())})
                .fetchOne();

        // Don't let them in if the account is locked or disabled.
        if(user.getState().equals(User.UserState.DISABLED.getStateText())
            || user.getState().equals(User.UserState.LOCKED.getStateText())){
            LOGGER.debug("Account {} tried to log in but it is disabled or locked.", credentials.getEmail());
            return unauthorisedAndLockedOrDisabledResponse();
        }

        boolean isPasswordCorrect = BCrypt.checkpw(credentials.getPassword(), user.getPasswordHash());
        if(isPasswordCorrect) {
            this.LOGGER.debug("Login for {} succeeded", credentials.getEmail());

            // We reset the failed login count if we have a successful login
            user.setLoginFailures(0);
            user.setLoginCount(user.getLoginCount() + 1);
            user.setLastLogin(UserMapper.convertISO8601ToTimestamp(ZonedDateTime.now().toString()));
            database
                    .update((Table) USERS)
                    .set(user)
                    .where(new Condition[]{USERS.EMAIL.eq(credentials.getEmail())}).execute();

            String token = this.tokenGenerator.getToken(credentials.getEmail());
            response = Response.status(Status.OK).entity(token).build();
            return response;
        } else {
            // If the password is wrong we need to increment the failed login count,
            // check if we need to locked the account, and save.
            user.setLoginFailures(user.getLoginFailures() + 1);
            boolean shouldLock = user.getLoginFailures() >= this.config.getFailedLoginLockThreshold();

            if(shouldLock){
                user.setState(User.UserState.LOCKED.getStateText());
            }

            database
                    .update((Table) USERS)
                    .set(user)
                    .where(new Condition[]{USERS.EMAIL.eq(credentials.getEmail())}).execute();

            LOGGER.debug("Password for {} is incorrect", credentials.getEmail());
            LOGGER.debug("Account {} has had too many failed access attempts and is locked", credentials.getEmail());

            return shouldLock ? unauthorisedAndNowLockedResponse() : unauthorisedResponse();
        }
    }

    private final Response redirectToLoginResponse() throws URISyntaxException {
        return Response.seeOther(new URI(this.config.getLoginUrl())).build();
    }

    private static final Response unauthorisedResponse() {
        return Response.status(Status.UNAUTHORIZED).entity("Invalid credentials").build();
    }

    private static final Response unauthorisedAndNowLockedResponse() {
        return Response.status(Status.UNAUTHORIZED).entity("Too many failed attempts - this account is now locked").build();
    }

    private static final Response unauthorisedAndLockedOrDisabledResponse() {
        return Response.status(Status.UNAUTHORIZED).entity("This account is locked or disabled").build();
    }
}
