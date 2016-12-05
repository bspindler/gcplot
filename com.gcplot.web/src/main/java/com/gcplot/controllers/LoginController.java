package com.gcplot.controllers;

import com.gcplot.commons.ErrorMessages;
import com.gcplot.commons.Utils;
import com.gcplot.commons.exceptions.NotUniqueException;
import com.gcplot.mail.MailService;
import com.gcplot.messages.ChangePasswordRequest;
import com.gcplot.messages.ChangeUsernameRequest;
import com.gcplot.messages.LoginResult;
import com.gcplot.messages.RegisterRequest;
import com.gcplot.model.account.Account;
import com.gcplot.model.account.AccountImpl;
import com.gcplot.repository.AccountRepository;
import com.gcplot.web.RequestContext;
import com.google.common.base.Strings;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Optional;
import java.util.regex.Pattern;

public class LoginController extends Controller {
    private AccountRepository accountRepository;
    private MailService mailService;
    private String uiProtocol;
    private String uiHost;
    private String uiConfirmPath;

    @PostConstruct
    public void init() {
        dispatcher.noAuth().filter(c -> c.hasParam("login") && c.hasParam("password"),
                "Username and password are required!").get("/user/login", this::login);
        dispatcher.noAuth().blocking().post("/user/register", RegisterRequest.class, this::register);
        dispatcher.requireAuth().allowNotConfirmed().get("/user/info", this::userInfo);
        dispatcher.requireAuth().allowNotConfirmed().filter(c -> c.hasParam("salt"),
                "Salt should be provided!").get("/user/confirm", this::confirm);
        dispatcher.requireAuth().allowNotConfirmed()
                .post("/user/change_password", ChangePasswordRequest.class, this::changePassword);
        dispatcher.requireAuth().allowNotConfirmed()
                .post("/user/change_username", ChangeUsernameRequest.class, this::changeUsername);
    }

    /**
     * GET /user/login
     * No Auth
     * Params:
     *   - login (String)
     *   - password (String)
     *
     * @param request
     */
    public void login(RequestContext request) {
        String login = request.param("login");
        String password = request.param("password");

        Optional<Account> r =
                getAccountRepository().account(login, hashPass(password), guess(login));
        if (r.isPresent()) {
            request.response(LoginResult.from(r.get()));
        } else {
            request.write(ErrorMessages.buildJson(ErrorMessages.WRONG_CREDENTIALS));
        }
    }

    /**
     * GET /user/info
     * Require Auth (token)
     * No params
     *
     * @param ctx
     */
    public void userInfo(RequestContext ctx) {
        ctx.response(LoginResult.from(account(ctx)));
    }

    /**
     * POST /user/register
     * No Auth
     * Body: RegisterRequest (JSON)
     *
     * @param request
     * @param c
     */
    public void register(RegisterRequest request, RequestContext c) {
        Account newAccount = AccountImpl.createNew(request.username, request.firstName, request.lastName,
                request.email, DigestUtils.sha256Hex(Utils.getRandomIdentifier()),
                hashPass(request.password), DigestUtils.sha256Hex(Utils.getRandomIdentifier()), new ArrayList<>());
        try {
            getAccountRepository().insert(newAccount);
        } catch (NotUniqueException e) {
            c.write(ErrorMessages.buildJson(ErrorMessages.NOT_UNIQUE_FIELDS, "Username/E-mail must be unique."));
            return;
        }
        if (getMailService() != null) {
            try {
                getMailService().sendConfirmationFor(newAccount);
            } catch (Throwable t) {
                LOG.error(t.getMessage(), t);
            }
        }
        c.response(SUCCESS);
    }

    /**
     * GET /user/confirm
     * Require Auth (token)
     * Params:
     *   - salt (String)
     *
     * @param ctx
     */
    public void confirm(RequestContext ctx) {
        if (getAccountRepository().confirm(token(ctx), ctx.param("salt"))) {
            ctx.redirect(buildConfirmUrl());
        } else {
            ctx.write(ErrorMessages.buildJson(ErrorMessages.INTERNAL_ERROR,
                    String.format("Can't confirm user [username=%s]", account(ctx).username())));
        }
    }

    /**
     * POST /user/change_password
     * Require Auth (token)
     * Body: ChangePasswordRequest (JSON)
     *
     * @param c
     */
    public void changePassword(ChangePasswordRequest req, RequestContext c) {
        if (req.oldPassword.equals(req.newPassword)) {
            c.write(ErrorMessages.buildJson(ErrorMessages.SAME_PASSWORD));
        } else {
            if (!Strings.isNullOrEmpty(req.oldPassword) && !account(c).passHash().equals(hashPass(req.oldPassword))) {
                c.write(ErrorMessages.buildJson(ErrorMessages.OLD_PASSWORD_MISMATCH));
            } else if (!Strings.isNullOrEmpty(req.salt) && !account(c).confirmationSalt().equals(req.salt)) {
                c.write(ErrorMessages.buildJson(ErrorMessages.INVALID_REQUEST_PARAM, "Invalid salt."));
            } else if (Strings.isNullOrEmpty(req.oldPassword) && Strings.isNullOrEmpty(req.salt)) {
                c.write(ErrorMessages.buildJson(ErrorMessages.INVALID_REQUEST_PARAM,
                        "You must provide either salt or an old password."));
            }
            if (accountRepository.changePassword(account(c), hashPass(req.newPassword))) {
                c.response(SUCCESS);
            } else {
                c.write(ErrorMessages.buildJson(ErrorMessages.INTERNAL_ERROR,
                        String.format("Can't change password to user [username=%s]", account(c).username())));
            }
        }
    }

    /**
     * POST /user/change_username
     * Require Auth (token)
     * Body: ChangeUsernameRequest (JSON)
     *
     * @param c
     */
    public void changeUsername(ChangeUsernameRequest req, RequestContext c) {
        if (!Strings.isNullOrEmpty(req.username)) {
            if (accountRepository.changeUsername(account(c), req.username)) {
                c.response(SUCCESS);
            } else {
                c.write(ErrorMessages.buildJson(ErrorMessages.USER_ALREADY_EXISTS, "This username is already taken!"));
            }
        } else {
            c.write(ErrorMessages.buildJson(ErrorMessages.INVALID_REQUEST_PARAM, "Username can't be empty!"));
        }
    }

    private String buildConfirmUrl() {
        return uiProtocol + "://" + uiHost + uiConfirmPath;
    }

    private AccountRepository.LoginType guess(String username) {
        return EMAIL_PATTERN.matcher(username).matches() ? AccountRepository.LoginType.EMAIL
                : AccountRepository.LoginType.USERNAME;
    }

    private String hashPass(String p) {
        return DigestUtils.sha1Hex(DigestUtils.md5(p));
    }

    public AccountRepository getAccountRepository() {
        return accountRepository;
    }
    @Autowired
    public void setAccountRepository(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public MailService getMailService() {
        return mailService;
    }
    @Autowired
    public void setMailService(MailService mailService) {
        this.mailService = mailService;
    }

    public void setUiProtocol(String uiProtocol) {
        this.uiProtocol = uiProtocol;
    }

    public void setUiHost(String uiHost) {
        this.uiHost = uiHost;
    }

    public void setUiConfirmPath(String uiConfirmPath) {
        this.uiConfirmPath = uiConfirmPath;
    }

    public static final Pattern EMAIL_PATTERN = Pattern.compile("(?:[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[" +
            "a-z0-9!#$%&'*+/=?^_`{|}~-]+)*|\"(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21\\x23-\\x5b\\x5d-\\x7f]|" +
            "\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])*\")@(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-" +
            "z0-9-]*[a-z0-9])?|\\[(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0" +
            "-9][0-9]?|[a-z0-9-]*[a-z0-9]:(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21-\\x5a\\x53-\\x7f]|\\\\[\\x01-\\" +
            "x09\\x0b\\x0c\\x0e-\\x7f])+)\\])");
}
