package com.sitionix.forgeai.it.infra;

import com.sitionix.forgeit.core.annotation.ForgeFeatures;
import com.sitionix.forgeit.core.api.ForgeIT;
import com.sitionix.forgeit.mockmvc.api.MockMvcSupport;

@ForgeFeatures({
        MockMvcSupport.class
})
public interface TestManager extends ForgeIT {
}
