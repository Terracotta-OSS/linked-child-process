/*
 * Copyright 2003-2008 Terracotta, Inc.
 * Copyright Super iPaaS Integration LLC, an IBM Company 2024
 */
package com.tc.lcp;


/**
 * Simple main class used by {@link LinkedJavaProcessTest} that prints out some environment.
 */
public class LinkedJavaProcessTestMain4 {

  public static void main(String[] args) {
    System.out.println("DATA: ljpt.foo=" + System.getProperty("ljpt.foo"));
    System.out.println("DATA: " + System.getProperty("user.dir"));
    
    System.out.flush();
  }

}
