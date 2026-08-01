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

class Job104FacetsClientTest {

    @Test
    void fetchFlattensNestedCategoryAndAreaTreesAtEveryDepth() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(containsString("/category-tool/json/JobCat.json")))
                .andRespond(withSuccess(fixture("job104-jobcat.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/category-tool/json/Area.json")))
                .andRespond(withSuccess(fixture("job104-area.json"), MediaType.APPLICATION_JSON));

        Job104FacetsClient client = new Job104FacetsClient(builder);

        SourceFacets facets = client.fetch();

        // 父節點（軟體／工程類人員、MIS／網管類人員）跟子節點（後端/全端工程師）
        // 都要出現——每一層都算獨立的可選值，不是只取葉節點
        assertThat(facets.categories()).extracting(Facet::id)
                .containsExactlyInAnyOrder("2007001000", "2007001016", "2007001017", "2007002000");
        assertThat(facets.categories()).extracting(Facet::name)
                .contains("軟體／工程類人員", "後端工程師", "全端工程師", "MIS／網管類人員");

        assertThat(facets.locations()).extracting(Facet::id)
                .containsExactlyInAnyOrder("6001000000", "6001001000", "6001001007", "6002000000");
        assertThat(facets.locations()).extracting(Facet::name)
                .contains("台北市", "台北市信義區", "台北市內湖區", "新北市");

        server.verify();
    }

    private String fixture(String name) throws Exception {
        try (var in = new ClassPathResource("fixtures/" + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
