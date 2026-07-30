package dev.jobradar.api.facets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class YouratorFacetsClientTest {

    @Test
    void fetchFiltersToAllowedGroupsAndExcludesTestingCategory() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(containsString("/api/v4/job_categories")))
                .andRespond(withSuccess(fixture("yourator-job-categories.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/api/v4/areas")))
                .andRespond(withSuccess(fixture("yourator-areas.json"), MediaType.APPLICATION_JSON));

        YouratorFacetsClient client = new YouratorFacetsClient(builder);

        SourceFacets facets = client.fetch();

        // group 7（軟體開發）留下後端/全端，「測試工程」被排除；group 6（軟硬體系統整合）
        // 整組排除；group 3（系統與基礎架構）保留；group 2（行銷）整組排除
        assertThat(facets.categories()).extracting(Facet::id)
                .containsExactlyInAnyOrder("後端工程", "全端工程", "雲端工程師", "系統架構師");
        assertThat(facets.categories()).noneMatch(f -> f.id().equals("測試工程"));
        assertThat(facets.categories()).noneMatch(f -> f.id().equals("硬體工程"));
        assertThat(facets.categories()).noneMatch(f -> f.id().equals("行銷企劃 / 社群經營"));

        assertThat(facets.locations()).containsExactly(new Facet("TPE", "臺北市"), new Facet("NWT", "新北市"));

        server.verify();
    }

    private String fixture(String name) throws Exception {
        try (var in = new ClassPathResource("fixtures/" + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
