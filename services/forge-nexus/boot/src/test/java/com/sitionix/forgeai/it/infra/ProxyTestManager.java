package com.sitionix.forgeai.it.infra;

import com.sitionix.forgeit.core.annotation.ForgeFeatures;
import com.sitionix.forgeit.core.api.ForgeIT;
import com.sitionix.forgeit.mockmvc.api.MockMvcSupport;
import com.sitionix.forgeit.mongodb.api.MongoSupport;
import com.sitionix.forgeit.wiremock.api.WireMockSupport;

@ForgeFeatures({
        MockMvcSupport.class,
        MongoSupport.class,
        WireMockSupport.class
})
public interface ProxyTestManager extends ForgeIT, MockMvcSupport, MongoSupport, WireMockSupport {
}
