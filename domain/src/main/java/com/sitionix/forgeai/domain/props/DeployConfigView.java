package com.sitionix.forgeai.domain.props;

public interface DeployConfigView {

    String getType();

    String getRepo();

    DeployUnitConfigView getService();

    DeployUnitConfigView getDb();
}
