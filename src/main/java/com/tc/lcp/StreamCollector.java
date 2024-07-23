/*
 * Copyright 2003-2008 Terracotta, Inc.
 * Copyright Super iPaaS Integration LLC, an IBM Company 2024
 */
package com.tc.lcp;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * An object that reads a stream asynchronously and collects it into a data buffer.
 */
public class StreamCollector extends StreamCopier {
  
  public StreamCollector(InputStream stream) {
    super(stream, new ByteArrayOutputStream());
  }
  
  public String toString() {
    return new String(((ByteArrayOutputStream) this.out).toByteArray());
  }

}
