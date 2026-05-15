package com.sitionix.forgeai.infrastructure.mongodb;

import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneDependency;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.infrastructure.mongodb.entity.LaneDependencyDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.LaneDocument;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import javax.annotation.processing.Generated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-05-15T19:38:36+0300",
    comments = "version: 1.6.2, compiler: javac, environment: Java 21.0.9 (Oracle Corporation)"
)
@Component
public class LaneEntityMapperImpl implements LaneEntityMapper {

    @Autowired
    private LaneDependencyEntityMapper laneDependencyEntityMapper;

    @Override
    public LaneDocument asLaneDocument(Lane lane) {
        if ( lane == null ) {
            return null;
        }

        Agent type = null;
        UUID id = null;
        String scope = null;
        LaneStatus status = null;
        int attempt = 0;
        UUID inputTaskId = null;
        Set<LaneDependencyDocument> dependsOn = null;

        type = lane.getAgent();
        id = lane.getId();
        scope = lane.getScope();
        status = lane.getStatus();
        attempt = lane.getAttempt();
        inputTaskId = lane.getInputTaskId();
        dependsOn = laneDependencySetToLaneDependencyDocumentSet( lane.getDependsOn() );

        LaneDocument laneDocument = new LaneDocument( id, type, scope, status, attempt, inputTaskId, dependsOn );

        return laneDocument;
    }

    @Override
    public Lane asLane(LaneDocument laneDocument) {
        if ( laneDocument == null ) {
            return null;
        }

        Lane.LaneBuilder lane = Lane.builder();

        lane.agent( laneDocument.getType() );
        lane.id( laneDocument.getId() );
        lane.scope( laneDocument.getScope() );
        lane.status( laneDocument.getStatus() );
        lane.attempt( laneDocument.getAttempt() );
        lane.inputTaskId( laneDocument.getInputTaskId() );
        lane.dependsOn( laneDependencyDocumentSetToLaneDependencySet( laneDocument.getDependsOn() ) );

        return lane.build();
    }

    protected Set<LaneDependencyDocument> laneDependencySetToLaneDependencyDocumentSet(Set<LaneDependency> set) {
        if ( set == null ) {
            return null;
        }

        Set<LaneDependencyDocument> set1 = LinkedHashSet.newLinkedHashSet( set.size() );
        for ( LaneDependency laneDependency : set ) {
            set1.add( laneDependencyEntityMapper.asLaneDependencyDocument( laneDependency ) );
        }

        return set1;
    }

    protected Set<LaneDependency> laneDependencyDocumentSetToLaneDependencySet(Set<LaneDependencyDocument> set) {
        if ( set == null ) {
            return null;
        }

        Set<LaneDependency> set1 = LinkedHashSet.newLinkedHashSet( set.size() );
        for ( LaneDependencyDocument laneDependencyDocument : set ) {
            set1.add( laneDependencyEntityMapper.asLaneDependency( laneDependencyDocument ) );
        }

        return set1;
    }
}
