package dev.jobradar.api.facets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.jobradar.common.source.Source;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class FacetsServiceTest {

    @Test
    void secondCallWithinTtlUsesCacheInsteadOfRefetching() {
        FacetsClient client = mock(FacetsClient.class);
        when(client.source()).thenReturn(Source.YOURATOR);
        SourceFacets facets = new SourceFacets(List.of(new Facet("後端工程", "後端工程")), List.of());
        when(client.fetch()).thenReturn(facets);

        FacetsService service = new FacetsService(List.of(client));

        SourceFacets first = service.getFacets("yourator");
        SourceFacets second = service.getFacets("yourator");

        assertThat(first).isEqualTo(facets);
        assertThat(second).isEqualTo(facets);
        // 快取生效的關鍵驗證：兩次呼叫，實際打平台 API 只發生一次
        verify(client, times(1)).fetch();
    }

    @Test
    void unknownSourceThrowsBadRequest() {
        FacetsClient client = mock(FacetsClient.class);
        when(client.source()).thenReturn(Source.YOURATOR);
        FacetsService service = new FacetsService(List.of(client));

        assertThatThrownBy(() -> service.getFacets("not-a-real-source"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }
}
