/*
 * Copyright 2003-2008 Terracotta, Inc.
 * Copyright Super iPaaS Integration LLC, an IBM Company 2024
 */
package com.tc.lcp;

public class LinkedJavaProcessTestMain6 {
  
  // simulates hung process
  public static void main(String[] args) throws InterruptedException {
    Thread.sleep(5 * 60 * 1000);
  }
}
