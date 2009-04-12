/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.lcp;

public class LinkedJavaProcessTestMain6 {
  
  // simulates hung process
  public static void main(String[] args) throws InterruptedException {
    Thread.currentThread().join();
  }
}
