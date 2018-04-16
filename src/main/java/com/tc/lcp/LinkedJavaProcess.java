/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.lcp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * A child Java process that uses a socket-based ping protocol to make sure that if the parent dies, the child dies a
 * short time thereafter. Useful for avoiding 'zombie child processes' when writing tests, etc. &mdash; otherwise, if
 * the parent process crashes or otherwise terminates abnormally, you'll get child processes accumulating until all hell
 * breaks loose on the box. </p>
 * <p>
 * Although it can't actually be related through Java inheritance (because {@link Process}is a class, not an interface),
 * this class behaves essentially identical to {@link Process}with two differences:
 * <ul>
 * <li>You instantiate this class directly, rather than getting an instance from {@link Runtime#exec}.</li>
 * <li>The process doesn't start until you call {@link #start}.</li>
 * </ul>
 */
public class LinkedJavaProcess extends Process {

  private File                     javaHome;
  private final String             mainClassName;
  private final List<String>       javaArguments;
  private final List<String>       arguments;
  private List<String>             environment;
  private File                     directory;
  private File                     javaExecutable;
  private long                     maxRuntime = 900;                                                        // in
                                                                                                             // seconds
  private String                   classpath;
  private ProcessExecutor          processExecutor;
  private boolean                  running;
  private boolean                  addL1Repos = true;
  private final List<StreamCopier> copiers    = Collections.synchronizedList(new ArrayList<StreamCopier>());


  public LinkedJavaProcess(String mainClassName, List<String> classArguments, List<String> jvmArgs) {
    this.mainClassName = mainClassName;
    this.javaArguments = jvmArgs == null ? new ArrayList<String>() : jvmArgs;
    this.arguments = classArguments == null ? new ArrayList<String>() : classArguments;
    this.environment = new ArrayList<String>();
    this.directory = null;
    this.javaExecutable = null;
    this.processExecutor = null;
    this.running = false;
  }

  public LinkedJavaProcess(String mainClassName) {
    this(mainClassName, new ArrayList<String>(), new ArrayList<String>());
  }

  public LinkedJavaProcess(String mainClassName, List<String> classArguments) {
    this(mainClassName, classArguments, new ArrayList<String>());
  }

  public void setMaxRuntime(long maxRuntime) {
    this.maxRuntime = maxRuntime;
  }

  public long getMaxRuntime() {
    return maxRuntime;
  }

  public void setClasspath(String classpath) {
    this.classpath = classpath;
  }

  public File getJavaHome() {
    return javaHome;
  }

  public void setJavaHome(File javaHome) {
    this.javaHome = javaHome;
  }

  public void setJavaExecutable(File javaExecutable) {
    this.javaExecutable = javaExecutable;
  }

  public void addAllJvmArgs(List<String> jvmArgs) {
    javaArguments.addAll(jvmArgs);
  }

  public void addJvmArg(String jvmArg) {
    javaArguments.add(jvmArg);
  }

  public void setEnvironment(List<String> environment) {
    this.environment = environment;
  }

  public void setDirectory(File directory) {
    this.directory = directory;
  }

  public void setAddL1Repos(boolean flag) {
    addL1Repos = flag;
  }

  @Override
  public synchronized void destroy() {
    if (!this.running) throw new IllegalStateException("This LinkedJavaProcess is not running.");
    this.processExecutor.destroy();
    this.running = false;
  }

  private synchronized void setJavaExecutableIfNecessary() throws IOException {
    if (this.javaExecutable == null) {
      if (javaHome == null) {
        javaHome = new File(System.getProperty("java.home"));
      }

      File javaBin = new File(javaHome, "bin");
      File javaPlain = new File(javaBin, "java");
      File javaExe = new File(javaBin, "java.exe");

      if (this.javaExecutable == null) {
        if (javaPlain.exists() && javaPlain.isFile()) this.javaExecutable = javaPlain;
      }

      if (this.javaExecutable == null) {
        if (javaExe.exists() && javaExe.isFile()) this.javaExecutable = javaExe;
      }

      if (this.javaExecutable == null) {
        // formatting
        throw new IOException("Can't find the Java binary; perhaps you need to set it yourself? Tried "
                              + javaPlain.getAbsolutePath() + " and " + javaExe.getAbsolutePath());
      }
    }
  }

  public synchronized void start() throws IOException {
    if (this.running) throw new IllegalStateException("This LinkedJavaProcess is already running.");

    HeartBeatService.startHeartBeatService();

    List<String> fullCommandList = new ArrayList<String>();
    List<String> allJavaArguments = new ArrayList<String>();

    File workingDir = directory != null ? directory : new File(System.getProperty("user.dir"));
    File generatedClasspathJar = generateClasspathJar(classpath != null ? classpath
                                                          : System.getProperty("java.class.path"), workingDir);

    String l1Repos = System.getProperty("com.tc.l1.modules.repositories");
    if (l1Repos != null && addL1Repos) {
      allJavaArguments.add("-Dcom.tc.l1.modules.repositories=" + l1Repos);
    }

    allJavaArguments.add("-Dlinked-java-process-max-runtime=" + maxRuntime);
    allJavaArguments.addAll(javaArguments);

    setJavaExecutableIfNecessary();

    int socketPort = HeartBeatService.listenPort();

    Map<String, String> env = makeEnvMap(environment);

    fullCommandList.add(javaExecutable.getAbsolutePath());
    fullCommandList.add("-classpath");
    fullCommandList.add(generatedClasspathJar.getAbsolutePath());
    fullCommandList.addAll(allJavaArguments);
    fullCommandList.add(LinkedJavaProcessStarter.class.getName());
    fullCommandList.add(Integer.toString(socketPort));
    fullCommandList.add(mainClassName);
    fullCommandList.addAll(arguments);

    String[] command = fullCommandList.toArray(new String[fullCommandList.size()]);

    System.err.println("Start java process with command: " + fullCommandList);
    this.processExecutor = ProcessExecutor.exec(command, env, workingDir);
    this.running = true;
  }


  private Map<String, String> makeEnvMap(List<String> list) {
    Map<String, String> rv = new HashMap(System.getenv());

    for (String string : list) {
      String[] nameValue = string.split("=", 2);
      rv.put(nameValue[0], nameValue[1]);
    }

    return rv;
  }

  /**
   * Java names these things a bit funny &mdash; this is the spawned process's <tt>stdout</tt>.
   */
  @Override
  public synchronized InputStream getInputStream() {
    if (!this.running) throw new IllegalStateException("This LinkedJavaProcess is not yet running.");
    return this.processExecutor.getInputStream();
  }

  public synchronized String[] getCommand() {
    if (!this.running) throw new IllegalStateException("This LinkedJavaProcess is not yet running.");
    return this.processExecutor.getCommand();
  }

  public InputStream STDOUT() {
    return getInputStream();
  }

  public OutputStream STDIN() {
    return getOutputStream();
  }

  public InputStream STDERR() {
    return getErrorStream();
  }

  public void mergeSTDOUT() {
    mergeSTDOUT(null);
  }

  public void mergeSTDOUT(String identifier) {
    mergeStream(STDOUT(), System.out, identifier);
  }

  public void mergeSTDERR() {
    mergeSTDERR(null);
  }

  public void mergeSTDERR(String identifier) {
    mergeStream(STDERR(), System.err, identifier);
  }

  private void mergeStream(InputStream in, OutputStream out, String identifier) {
    StreamCopier copier = new StreamCopier(in, out, identifier);
    copiers.add(copier);
    copier.start();
  }

  /**
   * This is the spawned process's <tt>stderr</tt>.
   */
  @Override
  public synchronized InputStream getErrorStream() {
    if (!this.running) throw new IllegalStateException("This LinkedJavaProcess is not yet running.");
    return this.processExecutor.getErrorStream();
  }

  /**
   * Java names these things a bit funny &mdash; this is the spawned process's <tt>stdin</tt>.
   */
  @Override
  public synchronized OutputStream getOutputStream() {
    if (!this.running) throw new IllegalStateException("This LinkedJavaProcess is not yet running.");
    return this.processExecutor.getOutputStream();
  }

  @Override
  public synchronized int exitValue() {
    if (this.processExecutor == null) throw new IllegalStateException("This LinkedJavaProcess has not been started.");
    int out = this.processExecutor.exitValue();
    this.running = false;
    return out;
  }

  @Override
  public int waitFor() throws InterruptedException {
    ProcessExecutor theProcessExecutor;

    synchronized (this) {
      if (!this.running) throw new IllegalStateException("This LinkedJavaProcess is not running.");
      theProcessExecutor = this.processExecutor;
    }

    int exitCode = theProcessExecutor.waitFor();

    for (Iterator<StreamCopier> i = copiers.iterator(); i.hasNext();) {
      Thread t = i.next();
      t.join();
      i.remove();
    }

    synchronized (this) {
      this.running = false;
    }

    return exitCode;
  }

  private File generateClasspathJar(String cp, File workingDir) throws IOException {
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().putValue("Class-Path", generateManifestClasspath(cp));
    File classpathJar = File.createTempFile("lcpclasspath", ".jar", workingDir);
    JarOutputStream target = null;
    try {
      target = new JarOutputStream(new FileOutputStream(classpathJar), manifest);
    } finally {
      if (target != null) {
        try {
          target.close();
        } catch (IOException e) {
          // ignore
        }
      }
    }
    return classpathJar;
  }

  private String generateManifestClasspath(String cp) throws IOException {
    String[] elements = cp.split(File.pathSeparator);
    StringBuilder sb = new StringBuilder();
    for (String element : elements) {
      element = element.trim();
      if (element.length() == 0) {
        continue;
      }

      File f = new File(element);
      if (f.exists()) {
        sb.append(f.toURI().toURL().toString()).append(" ");
      } else {
        System.out.println("LCP: path element [" + element + "] doesn't exist, ignoring");
      }
    }
    if (sb.length() > 1) {
      sb.deleteCharAt(sb.length() - 1);
    }
    return sb.toString();
  }

}
