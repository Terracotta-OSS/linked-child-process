/*
 * Copyright 2003-2008 Terracotta, Inc.
 * Copyright Super iPaaS Integration LLC, an IBM Company 2024
 */
package com.tc.lcp;

/**
 * A simple child process for {@link LinkedJavaProcessTest} that prints something simple and exits.
 */
public class LinkedJavaProcessTestMain1 {

  public static void main(String[] args) {
    System.out.println("DATA: Hi there!");
    System.err.println("DATA: Ho there!");
  }

}
