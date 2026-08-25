package com.sitionix.forgeagent.application.usecase;

public record SaveSshConnectionCommand(String name,String host,int port,String username,String privateKeyPath) { }
