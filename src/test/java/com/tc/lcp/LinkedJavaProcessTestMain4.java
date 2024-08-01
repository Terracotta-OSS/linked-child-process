/*
 * Copyright 2003-2008 Terracotta, Inc.
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
