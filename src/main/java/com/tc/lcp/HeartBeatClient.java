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
 * limitations under the License. */
package com.tc.lcp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

public class HeartBeatClient extends Thread {
  private static final int  HEARTBEAT_TIMEOUT = HeartBeatServer.PULSE_INTERVAL * 2;
  private static DateFormat DATEFORMAT        = new SimpleDateFormat(
                                                  "HH:mm:ss.SSS");

  private Socket            socket;
  private boolean           isAppServer       = false;
  private String            clientName;
  private int               missedPulse       = 0;
  private int               listenPort;

  public HeartBeatClient(int listenPort, String clientName, boolean isAppServer) {
    this.isAppServer = isAppServer;
    this.clientName = clientName;
    this.listenPort = listenPort;
    createSocket();
  }

  private void createSocket() {
    try {
      socket = new Socket("localhost", listenPort);
      socket.setSoTimeout(HEARTBEAT_TIMEOUT);
      socket.setTcpNoDelay(true);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static void log(String message) {
    System.out.println(DATEFORMAT.format(new Date()) + " - HeartBeatClient: "
        + message);
  }

  public void run() {
    BufferedReader in = null;
    PrintWriter out = null;
    try {
      in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
      out = new PrintWriter(socket.getOutputStream(), true);

      // introduce myself to the server
      // sending clientName
      out.println(clientName + ":" + socket.getLocalPort());
      log("Sent my name [" + clientName + "] to heartbeat server");
      while (true) {
        try {
          // will time out if it didn't get any pulse from server
          String signal = in.readLine();
          if (signal == null) {
            throw new Exception("Null signal");
          } else if (HeartBeatServer.PULSE.equals(signal)) {
            log("Received pulse from heartbeat server, port "
                + socket.getLocalPort());
            out.println(signal);
            missedPulse = 0;
          } else if (HeartBeatServer.KILL.equals(signal)) {
            log("Received KILL from heartbeat server. Killing self.");
            System.exit(1);
          } else if (HeartBeatServer.IS_APP_SERVER_ALIVE.equals(signal)) {
            log("Received IS_APP_SERVER_ALIVE from heartbeat server. ");
            if (isAppServer) {
              out.println(HeartBeatServer.IM_ALIVE);
              log("  responded: IM_ALIVE");
            } else {
              out.println("NOT_AN_APP_SERVER");
              log("  responded: NOT_AN_APP_SERVER");
            }
          } else {
            throw new Exception("Unknown signal");
          }
        } catch (SocketTimeoutException toe) {
          log("No pulse received for " + (HEARTBEAT_TIMEOUT / 1000)
              + " seconds");
          log("Missed pulse count: " + missedPulse);
          if (missedPulse >= HeartBeatServer.MISS_ALLOW) {
            log("Missing " + HeartBeatServer.MISS_ALLOW + " pulses from HeartBeatServer, killing self");
            System.exit(-1);
          }
          missedPulse++;
        } catch (SocketException e) {
          log("Got a Socket exception: " + e.getMessage() + ". Parent may have died, killing self");
          System.exit(-1);
        }
      }
    } catch (Exception e) {
      log("Caught exception in heartbeat client. Killing self.");
      e.printStackTrace();
      System.exit(-2);
    } finally {
      try {
        socket.close();
      } catch (Exception e) {
        // ignored
      }
    }
  }

}
