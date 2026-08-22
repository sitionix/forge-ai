package com.sitionix.forgeproxyit.infra;

import com.sitionix.forgeit.core.annotation.ForgeFeatures;
import com.sitionix.forgeit.core.api.ForgeIT;
import com.sitionix.forgeit.mockmvc.api.MockMvcSupport;
import com.sitionix.forgeit.wiremock.api.WireMockSupport;

@ForgeFeatures({MockMvcSupport.class, WireMockSupport.class})
public interface NexusProxyTestManager extends ForgeIT {
}
