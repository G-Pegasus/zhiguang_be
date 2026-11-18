package com.tongji.auth.service;

import com.tongji.auth.api.dto.AuthResponse;
import com.tongji.auth.api.dto.PasswordResetRequest;
import com.tongji.auth.api.dto.RegisterRequest;
import com.tongji.auth.audit.LoginLogService;
import com.tongji.auth.config.AuthProperties;
import com.tongji.auth.model.ClientInfo;
import com.tongji.auth.model.IdentifierType;
import com.tongji.auth.token.JwtService;
import com.tongji.auth.token.RefreshTokenStore;
import com.tongji.auth.token.TokenPair;
import com.tongji.auth.verification.VerificationCheckResult;
import com.tongji.auth.verification.VerificationCodeStatus;
import com.tongji.auth.verification.VerificationScene;
import com.tongji.auth.verification.VerificationService;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.user.domain.User;
import com.tongji.user.service.UserService;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AuthService} 注册/重置密码流程的校验顺序回归测试。
 * <p>
 * 背景：验证码校验成功后会立即从存储中删除（一次性凭证），因此任何「可能因用户输入而失败」的
 * 校验都必须排在验证码校验之前。否则用户先填对验证码、填错密码时，验证码被消耗；
 * 改正密码后重试只会拿到「验证码不存在或已过期」。见 {@code RedisVerificationCodeStore#verify}。
 */
class AuthServiceTest {

    private static final ClientInfo CLIENT = new ClientInfo("127.0.0.1", "JUnit");
    private static final String PHONE = "13800000000";
    private static final String CODE = "123456";
    /** 满足策略：长度 ≥ 8 且同时包含字母与数字。 */
    private static final String STRONG_PASSWORD = "zhiguang2026";
    /** 违反策略：纯数字，缺少字母。 */
    private static final String WEAK_PASSWORD = "12345678";

    private UserService userService;
    private VerificationService verificationService;
    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private RefreshTokenStore refreshTokenStore;
    private LoginLogService loginLogService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        verificationService = mock(VerificationService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtService = mock(JwtService.class);
        refreshTokenStore = mock(RefreshTokenStore.class);
        loginLogService = mock(LoginLogService.class);
        AuthProperties properties = new AuthProperties();
        authService = new AuthService(
                userService,
                verificationService,
                passwordEncoder,
                jwtService,
                refreshTokenStore,
                loginLogService,
                properties
        );
    }

    @Test
    @DisplayName("密码不合规时不得触碰验证码（验证码保持可用）")
    void weakPasswordShouldNotConsumeVerificationCode() {
        when(userService.existsByPhone(PHONE)).thenReturn(false);

        assertBusinessError(
                () -> authService.register(registerRequest(WEAK_PASSWORD, CODE), CLIENT),
                ErrorCode.PASSWORD_POLICY_VIOLATION
        );

        // 关键断言：验证码从未被校验，也就不会被删除，用户改正密码后可直接重试。
        verifyNoInteractions(verificationService);
        verify(userService, never()).createUser(any(User.class));
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    @DisplayName("验证码与密码同时错误时，优先报密码错误且不消耗验证码")
    void weakPasswordShouldBeReportedBeforeWrongCode() {
        when(userService.existsByPhone(PHONE)).thenReturn(false);

        assertBusinessError(
                () -> authService.register(registerRequest(WEAK_PASSWORD, "000000"), CLIENT),
                ErrorCode.PASSWORD_POLICY_VIOLATION
        );

        verifyNoInteractions(verificationService);
        verify(userService, never()).createUser(any(User.class));
    }

    @Test
    @DisplayName("密码合规时先校验验证码、再落库，成功后写入刷新令牌白名单")
    void validRequestShouldVerifyCodeThenPersist() {
        when(userService.existsByPhone(PHONE)).thenReturn(false);
        when(verificationService.verify(VerificationScene.REGISTER, PHONE, CODE))
                .thenReturn(new VerificationCheckResult(VerificationCodeStatus.SUCCESS, 0, 5));
        when(passwordEncoder.encode(STRONG_PASSWORD)).thenReturn("{bcrypt}hash");
        when(userService.createUser(any(User.class))).thenAnswer(invocation -> {
            User created = invocation.getArgument(0);
            created.setId(1L);
            return created;
        });
        Instant accessExpiry = Instant.now().plusSeconds(900);
        Instant refreshExpiry = Instant.now().plusSeconds(604_800);
        when(jwtService.issueTokenPair(any(User.class))).thenReturn(
                new TokenPair("access-token", accessExpiry, "refresh-token", refreshExpiry, "jti-1")
        );

        AuthResponse response = authService.register(registerRequest(STRONG_PASSWORD, CODE), CLIENT);

        assertThat(response.user().id()).isEqualTo(1L);
        assertThat(response.token().accessToken()).isEqualTo("access-token");
        InOrder order = inOrder(verificationService, userService);
        order.verify(verificationService).verify(VerificationScene.REGISTER, PHONE, CODE);
        order.verify(userService).createUser(any(User.class));
        verify(refreshTokenStore).storeToken(eq(1L), eq("jti-1"), any());
        verify(loginLogService).record(eq(1L), eq(PHONE), eq("REGISTER"), eq(CLIENT.ip()), eq(CLIENT.userAgent()), eq("SUCCESS"));
    }

    @Test
    @DisplayName("密码合规但验证码错误时不创建用户")
    void wrongCodeShouldFailBeforeCreatingUser() {
        when(userService.existsByPhone(PHONE)).thenReturn(false);
        when(verificationService.verify(VerificationScene.REGISTER, PHONE, CODE))
                .thenReturn(new VerificationCheckResult(VerificationCodeStatus.MISMATCH, 1, 5));

        assertBusinessError(
                () -> authService.register(registerRequest(STRONG_PASSWORD, CODE), CLIENT),
                ErrorCode.VERIFICATION_MISMATCH
        );

        verify(userService, never()).createUser(any(User.class));
        verifyNoInteractions(passwordEncoder);
        verify(refreshTokenStore, never()).storeToken(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("重置密码同样要先校验密码策略，再消耗验证码")
    void resetPasswordShouldValidatePasswordBeforeConsumingCode() {
        PasswordResetRequest request = new PasswordResetRequest(IdentifierType.PHONE, PHONE, CODE, WEAK_PASSWORD);

        assertBusinessError(
                () -> authService.resetPassword(request),
                ErrorCode.PASSWORD_POLICY_VIOLATION
        );

        verifyNoInteractions(verificationService);
        verifyNoInteractions(passwordEncoder);
    }

    /**
     * 构造注册请求。
     *
     * @param password 密码明文。
     * @param code     验证码。
     * @return 注册请求。
     */
    private static RegisterRequest registerRequest(String password, String code) {
        return new RegisterRequest(IdentifierType.PHONE, PHONE, code, password, true);
    }

    /**
     * 断言调用抛出指定业务错误码的 {@link BusinessException}。
     *
     * @param callable 待执行调用。
     * @param expected 期望错误码。
     */
    private static void assertBusinessError(ThrowingCallable callable, ErrorCode expected) {
        assertThatThrownBy(callable)
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(expected);
    }
}
