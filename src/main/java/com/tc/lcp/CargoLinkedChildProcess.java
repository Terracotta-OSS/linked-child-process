/*
 * Copyright 2003-2006 Terracotta, Inc.
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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.Properties;


/**
 * This class serves three purposes. It delegates to {@link HeartBeatService} to page the parent process.
 * And it loads a properties file which was written by the parent process (in the same directory as the log) and sets
 * all name value pairs as system properties for the appserver's JVM. This makes these available to servlets running in
 * the container.
 */
public final class CargoLinkedChildProcess {

  private static File instanceDir;

  private CargoLinkedChildProcess() {
    // cannot instantiate
  }

  public static void main(String[] args) throws Exception {
    String className = args[0];
    int port = Integer.parseInt(args[1]);
    instanceDir = new File(args[2]);

    String[] serverArgs = new String[0];
    if (args.length > 3) {
      serverArgs = new String[args.length - 3];
      for (int i = 3; i < args.length; i++) {
        serverArgs[i - 3] = args[i];
      }
    }

    System.out.println("JAVA VERSION: " + System.getProperty("java.version"));

    HeartBeatService.registerForHeartBeat(port, className, true);
    loadProperties();

    try {
      Class startServer = Class.forName(className);
      Method main = startServer.getMethod("main", new Class[] { String[].class });
      main.invoke(null, new Object[] { serverArgs });

    } catch (ClassNotFoundException cnfe) {
      System.err.println("unable to locate server class: " + className);
      cnfe.printStackTrace();
    } catch (NoSuchMethodException nsme) {
      System.err.println("unable to access method: main()");
      nsme.printStackTrace();
    }
  }

  private static void loadProperties() {
    File sandbox = instanceDir.getParentFile();
    Properties props = new Properties();
    try {
      props.load(new FileInputStream(new File(sandbox + File.separator + instanceDir.getName() + ".properties")));
    } catch (IOException ioe) {
      throw new RuntimeException("Unable to load properties file: " + sandbox + File.separator + instanceDir.getName()
                                 + ".properties");
    }
    String name = null;
    for (Enumeration e = props.propertyNames(); e.hasMoreElements();) {
      name = (String) e.nextElement();
      System.setProperty(name, props.getProperty(name));
    }
  }

}
