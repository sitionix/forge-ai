package com.sitionix.forgeai.infrastructure.mongodb;

import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneDependency;
import com.sitionix.forgeai.infrastructure.mongodb.entity.LaneDependencyDocument;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-05-15T19:38:36+0300",
    comments = "version: 1.6.2, compiler: javac, environment: Java 21.0.9 (Oracle Corporation)"
)
@Component
public class LaneDependencyEntityMapperImpl implements LaneDependencyEntityMapper {

    @Override
    public LaneDependencyDocument asLaneDependencyDocument(LaneDependency laneDependency) {
        if ( laneDependency == null ) {
            return null;
        }

        Agent type = null;
        String scope = null;

        type = laneDependency.getType();
        scope = laneDependency.getScope();

        LaneDependencyDocument laneDependencyDocument = new LaneDependencyDocument( type, scope );

        return laneDependencyDocument;
    }

    @Override
    public LaneDependency asLaneDependency(LaneDependencyDocument laneDependencyDocument) {
        if ( laneDependencyDocument == null ) {
            return null;
        }

        LaneDependency.LaneDependencyBuilder laneDependency = LaneDependency.builder();

        laneDependency.type( laneDependencyDocument.getType() );
        laneDependency.scope( laneDependencyDocument.getScope() );

        return laneDependency.build();
    }
}
