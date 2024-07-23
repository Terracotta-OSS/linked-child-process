/*
 * Copyright 2003-2007 Terracotta, Inc.
 * Copyright Super iPaaS Integration LLC, an IBM Company 2024
 */
package com.tc.lcp;

public class HeartBeatService {
  private static HeartBeatServer server;

  public static synchronized void startHeartBeatService() {
    if (server == null) {
      server = new HeartBeatServer();
      server.start();
    }
  }
  
  public static synchronized void stopHeartBeatServer() {
    if (server != null) {
      server.shutdown();
      server = null;
    }
  }
  
  public static synchronized int listenPort() {
    ensureServerHasStarted();
    return server.listeningPort();
  }
  
  public static synchronized void registerForHeartBeat(int listenPort, String clientName) {
    registerForHeartBeat(listenPort, clientName, false);
  }
  
  public static synchronized void registerForHeartBeat(int listenPort, String clientName, boolean isAppServer) {
    ensureServerHasStarted();
    HeartBeatClient client = new HeartBeatClient(listenPort, clientName, isAppServer);
    client.setDaemon(true);
    client.start();
  }
  
  public static synchronized void sendKillSignalToChildren() {
    ensureServerHasStarted();
    server.sendKillSignalToChildren();
  }
  
  public static synchronized boolean anyAppServerAlive() {
    ensureServerHasStarted();
    return server.anyAppServerAlive();
  }
  
  private static void ensureServerHasStarted() {
    if (server == null) new IllegalStateException("Heartbeat service has not started yet!");
  }
}
