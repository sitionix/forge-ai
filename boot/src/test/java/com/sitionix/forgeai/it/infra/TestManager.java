package com.sitionix.forgeai.it.infra;

import com.sitionix.forgeit.core.annotation.ForgeFeatures;
import com.sitionix.forgeit.core.api.ForgeIT;
import com.sitionix.forgeit.mockmvc.api.MockMvcSupport;
import com.sitionix.forgeit.mongodb.api.MongoSupport;

@ForgeFeatures({
        MockMvcSupport.class,
        MongoSupport.class
})
public interface TestManager extends ForgeIT {
}
