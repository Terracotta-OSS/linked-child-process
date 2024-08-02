/*
 * Copyright 2003-2007 Terracotta, Inc.
 * Copyright Super iPaaS Integration LLC, an IBM Company 2024
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
