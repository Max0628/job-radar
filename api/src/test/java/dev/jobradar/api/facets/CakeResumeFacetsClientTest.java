package dev.jobradar.api.facets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class CakeResumeFacetsClientTest {

    @Test
    void fetchFiltersToItPrefixMinusExcludedCodesAndTaiwanLocations() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(containsString("/api/client/v1/jobs/search")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(fixture("cakeresume-facets-search.json"), MediaType.APPLICATION_JSON));

        CakeResumeFacetsClient client = new CakeResumeFacetsClient(builder, new ObjectMapper());

        SourceFacets facets = client.fetch();

        // it_qa-test-engineer 跟 it_firmware-engineering 被排除（測試/硬體），
        // customer-service_retail-salesperson 不是 it_ 開頭本來就不會進來
        assertThat(facets.categories()).extracting(Facet::id)
                .containsExactlyInAnyOrder("it_back-end-engineer", "it_devops-system-admin");
        assertThat(facets.categories()).extracting(Facet::name)
                .containsExactlyInAnyOrder("Back End Engineer", "Devops System Admin");

        // 只留含「台灣」的地點，Vietnam/Taiwan（英文）被濾掉
        assertThat(facets.locations()).extracting(Facet::id)
                .containsExactlyInAnyOrder("台灣", "台北市, 台灣");

        server.verify();
    }

    private String fixture(String name) throws Exception {
        try (var in = new ClassPathResource("fixtures/" + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
