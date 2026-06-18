package com.sitionix.forgeai.it.infra;

import com.sitionix.forgeit.core.annotation.ForgeFeatures;
import com.sitionix.forgeit.core.api.ForgeIT;
import com.sitionix.forgeit.mockmvc.api.MockMvcSupport;
import com.sitionix.forgeit.mongodb.api.MongoSupport;
import com.sitionix.forgeit.sqlite.api.SqliteSupport;

@ForgeFeatures({
        MockMvcSupport.class,
        MongoSupport.class,
        SqliteSupport.class
})
public interface KnowledgeTestManager extends ForgeIT, MockMvcSupport, MongoSupport, SqliteSupport {
}
