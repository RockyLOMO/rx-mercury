package org.rx.crawler.config;

import com.ctrip.framework.apollo.openapi.client.ApolloOpenApiClient;
import com.ctrip.framework.apollo.openapi.dto.NamespaceReleaseDTO;
import com.ctrip.framework.apollo.openapi.dto.OpenItemDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ApolloConfig {
    @Value("${app.id}")
    private String appId;
    @Value("${apollo.portalUrl}")
    private String apolloPortalUrl;
    @Value("${apollo.openToken}")
    private String apolloToken;

    private ApolloOpenApiClient getApolloApiClient() {
        return ApolloOpenApiClient.newBuilder()
                .withPortalUrl(apolloPortalUrl)
                .withToken(apolloToken)
                .build();
    }

    public void setProperty(String key, String value) {
        ApolloOpenApiClient client = getApolloApiClient();
        OpenItemDTO item = new OpenItemDTO();
        item.setKey(key);
        item.setValue(value);
        client.createOrUpdateItem(appId, "pro", "default", "application", item);
        NamespaceReleaseDTO releaseDTO = new NamespaceReleaseDTO();
        releaseDTO.setReleaseTitle(String.format("openapi-%s", key));
        client.publishNamespace(appId, "pro", "default", "application", releaseDTO);
    }
}
