package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static io.restassured.RestAssured.given;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.common.QuarkusTestResource;
import io.github.bassemzohdy.openworkflow.agentic.identity.FakeIdpResource;
import java.time.Duration;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value=FakeIdpResource.class, restrictToAnnotatedClass=true)
class ManualEffectsDisabledApiTest {
    @Test void defaultApplicationAuthenticatesButExposesNoManualResolutionWithoutExplicitActivation() throws Exception {
        var claims=FakeIdpResource.idp().baseClaims("operator",null,Duration.ofMinutes(5));
        claims.put("aud",FakeIdpResource.AUDIENCE);claims.put("tenant","manual-disabled");claims.put("scope","oa.recover");
        String bearer=FakeIdpResource.idp().signClaims(claims);
        given().get("/operations/instances/00000000000000000000000000/effects/unknown").then().statusCode(401);
        given().auth().oauth2(bearer).get("/operations/instances/00000000000000000000000000/effects/unknown").then().statusCode(404);
        given().auth().oauth2(bearer).contentType("application/json").body("{}")
                .post("/operations/instances/00000000000000000000000000/effects/efp1-invalid/resolve")
                .then().statusCode(404);
    }
}
