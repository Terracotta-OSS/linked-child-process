/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.lcp;

import java.lang.reflect.Method;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Runs another main class, with full arguments, but first establishes a socket
 * heartbeat protocol with a parent process on a specified port &mdash; and
 * kills itself if this ping protocol is broken. This prevents runaway Java
 * processes.
 */
public class LinkedJavaProcessStarter {

  public static void main(String args[]) throws Exception {
    int pingPort = Integer.parseInt(args[0]);
    String childClass = args[1];

    String[] realArgs = new String[args.length - 2];
    if (realArgs.length > 0)
      System.arraycopy(args, 2, realArgs, 0, realArgs.length);

    HeartBeatService.registerForHeartBeat(pingPort, childClass);

    scheduleShutdownTimer();

    final Class mainClass = Class.forName(childClass);
    Method mainMethod = mainClass.getMethod("main",
        new Class[] { String[].class });
    mainMethod.invoke(null, new Object[] { realArgs });
  }

  public static long getMaxRuntime() {
    return Long.parseLong(System.getProperty("linked-java-process-max-runtime",
        "0"));
  }

  public static void scheduleShutdownTimer() {
    long maxRuntimeInSeconds = getMaxRuntime();
    if (maxRuntimeInSeconds > 0) {
      Timer timer = new Timer(true);
      timer.schedule(new ShutdownTask(), maxRuntimeInSeconds * 1000);
    }
  }

  static class ShutdownTask extends TimerTask {
    public void run() {
      System.err.println("Max runtime hit (" + getMaxRuntime()
          + "s). Force exit");
      System.err.flush();
      System.exit(255);
    }
  }
}