package app.carecontinuo.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

@Configuration
@ConditionalOnWebApplication
@Profile("preprod & !worker")
class StaffOidc {
    @Bean
    ClientRegistrationRepository staffLogin(Environment env) {
        String issuer = env.getRequiredProperty("CARE_OIDC_ISSUER");
        String backchannel = env.getProperty("CARE_OIDC_BACKCHANNEL", issuer);
        String origin = env.getRequiredProperty("CARE_PREPROD_ORIGIN");
        if (!issuer.startsWith("https://") || !origin.startsWith("https://")) {
            throw new IllegalArgumentException("Staff login requires HTTPS issuer and public origin");
        }
        var staff = ClientRegistration.withRegistrationId("hospital")
                .clientId(env.getProperty("CARE_OIDC_CLIENT_ID", "carecontinuo"))
                .clientSecret(env.getRequiredProperty("care_oidc_client_secret"))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
                .scope("openid")
                .issuerUri(issuer)
                .authorizationUri(env.getProperty("CARE_OIDC_AUTHORIZATION_URI", issuer + "/protocol/openid-connect/auth"))
                .tokenUri(env.getProperty("CARE_OIDC_TOKEN_URI", backchannel + "/protocol/openid-connect/token"))
                .jwkSetUri(env.getProperty("CARE_OIDC_JWK_SET_URI", backchannel + "/protocol/openid-connect/certs"))
                .redirectUri(origin + "/login/oauth2/code/hospital")
                .clientName("医院身份认证")
                .build();
        return new InMemoryClientRegistrationRepository(staff);
    }
}
