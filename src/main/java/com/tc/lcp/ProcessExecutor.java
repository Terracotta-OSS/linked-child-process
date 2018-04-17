package com.tc.lcp;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.tc.lcp.win32.Kernel32;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Map;

public abstract class ProcessExecutor {

  public static ProcessExecutor exec(String[] command, Map<String, String> env, File workingDir) throws IOException {
    fixupEnvironment(env);
    if (isWindows()) {
      return new Win32ProcessExecutor(command, env, workingDir);
    } else {
      return new JavaProcessExecutor(command, env, workingDir);
    }
  }

  private static boolean isWindows() {
    return System.getProperty("os.name").indexOf("Windows") >= 0;
  }

  private static void fixupEnvironment(Map<String, String> env) {
    if (isWindows()) {
      // A bunch of name lookup stuff will fail w/o setting SYSTEMROOT. Also, if
      // you have apple's rendevous/bonjour
      // client installed, it needs to be in the PATH such that dnssd.dll will
      // be found when using DNS

      if (!env.containsKey("SYSTEMROOT")) {
        String root = ":\\Windows";
        char i;
        for (i = 'c'; i <= 'z'; i++) {
          if (new File(i + root).exists()) {
            root = i + root;
            break;
          }
        }
        if (i > 'z') throw new RuntimeException("Can't find windir");
        env.put("SYSTEMROOT", root);
      }

      String crappleDirs = "C:\\Program Files\\Rendezvous\\" + File.pathSeparator + "C:\\Program Files\\Bonjour\\";

      if (!env.containsKey("PATH")) {
        env.put("PATH", crappleDirs);
      } else {
        String path = env.get("PATH");
        path = path + File.pathSeparator + crappleDirs;
        env.put("PATH", path);
      }
    }
  }

  public abstract void destroy();

  public abstract InputStream getInputStream();

  public abstract String[] getCommand();

  public abstract InputStream getErrorStream();

  public abstract OutputStream getOutputStream();

  public abstract int exitValue();

  public abstract int waitFor() throws InterruptedException;

  static class JavaProcessExecutor extends ProcessExecutor {
    private Process process;
    private String[] command;

    JavaProcessExecutor(String[] command, Map<String, String> env, File workingDir) throws IOException {
      this.command = command;
      this.process = Runtime.getRuntime().exec(command, makeEnv(env), workingDir);
    }

    private static String[] makeEnv(Map<String, String> env) {
      int i = 0;
      String[] rv = new String[env.size()];
      for (Iterator<String> iter = env.keySet().iterator(); iter.hasNext(); i++) {
        String key = iter.next();
        rv[i] = key + "=" + env.get(key);
      }
      return rv;
    }


    @Override
    public void destroy() {
      process.destroy();
    }

    @Override
    public InputStream getInputStream() {
      return process.getInputStream();
    }

    @Override
    public String[] getCommand() {
      return command;
    }

    @Override
    public InputStream getErrorStream() {
      return process.getErrorStream();
    }

    @Override
    public OutputStream getOutputStream() {
      return process.getOutputStream();
    }

    @Override
    public int exitValue() {
      // Process.exitValue() throws an exception if not yet terminated, so we know
      // it's terminated now.
      return process.exitValue();
    }

    @Override
    public int waitFor() throws InterruptedException {
      return process.waitFor();
    }
  }

  /**
   * Based on: https://msdn.microsoft.com/en-us/library/windows/desktop/ms682499(v=vs.85).aspx
   */
  static class Win32ProcessExecutor extends ProcessExecutor {

    private static final int TERMINATE_EXIT_CODE = 1;

    private Win32PipeInputStream inputStream;
    private Win32PipeOutputStream outputStream;
    private Win32PipeInputStream errorStream;
    private WinBase.PROCESS_INFORMATION.ByReference processInfo;
    private WinNT.HANDLE hJob;

    private String[] command;
    private int exitCode = -1;
    private boolean running;

    Win32ProcessExecutor(String[] command, Map<String, String> env, File workingDir) throws IOException {
      this.command = command;
      StringBuilder commandLineStringBuilder = new StringBuilder();
      for (String c : command) {
        commandLineStringBuilder.append("\"").append(c).append("\" ");
      }
      commandLineStringBuilder.setCharAt(commandLineStringBuilder.length() - 1, '\0');

      WinNT.HANDLE hChildStd_IN_Rd;
      WinNT.HANDLE hChildStd_IN_Wr;
      WinNT.HANDLE hChildStd_OUT_Rd;
      WinNT.HANDLE hChildStd_OUT_Wr;
      WinNT.HANDLE hChildStd_ERR_Rd;
      WinNT.HANDLE hChildStd_ERR_Wr;

      WinNT.HANDLE[] handles = createPipe(false);
      hChildStd_IN_Rd = handles[0];
      hChildStd_IN_Wr = handles[1];

      handles = createPipe(true);
      hChildStd_OUT_Rd = handles[0];
      hChildStd_OUT_Wr = handles[1];

      handles = createPipe(true);
      hChildStd_ERR_Rd = handles[0];
      hChildStd_ERR_Wr = handles[1];

      hJob = Kernel32.INSTANCE.CreateJobObject(null, null);
      if (hJob.getPointer() == null) {
        throw new IOException("Cannot create job object : " + Kernel32.INSTANCE.GetLastError());
      }

      Kernel32.JOBJECT_EXTENDED_LIMIT_INFORMATION jeli = new Kernel32.JOBJECT_EXTENDED_LIMIT_INFORMATION.ByReference();
      jeli.BasicLimitInformation.LimitFlags = Kernel32.JOB_OBJECT_LIMIT_BREAKAWAY_OK | Kernel32.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;

      if (!Kernel32.INSTANCE.SetInformationJobObject(hJob, Kernel32.JobObjectExtendedLimitInformation, jeli.getPointer(), jeli.size())) {
        throw new IOException("Unable to set extended limit information on the job object : " + Kernel32.INSTANCE.GetLastError());
      }

      // crete job in sandbox with own global atom table
      Kernel32.JOBOBJECT_BASIC_UI_RESTRICTIONS uli = new Kernel32.JOBOBJECT_BASIC_UI_RESTRICTIONS.ByReference();
      uli.UIRestrictionsClass = Kernel32.JOB_OBJECT_UILIMIT_GLOBALATOMS;

      if (!Kernel32.INSTANCE.SetInformationJobObject(hJob, Kernel32.JobObjectBasicUIRestrictions, uli.getPointer(), uli.size())) {
        throw new IOException("Unable to set ui limit information on the job object : " + Kernel32.INSTANCE.GetLastError());
      }

      WinBase.STARTUPINFO startupInfo = new WinBase.STARTUPINFO();
      startupInfo.hStdInput = hChildStd_IN_Rd;
      startupInfo.hStdOutput = hChildStd_OUT_Wr;
      startupInfo.hStdError = hChildStd_ERR_Wr;
      startupInfo.dwFlags |= WinBase.STARTF_USESTDHANDLES;

      WinBase.PROCESS_INFORMATION.ByReference processInformation = new WinBase.PROCESS_INFORMATION.ByReference();

      WinDef.DWORD creationFlags = new WinDef.DWORD(
          Kernel32.CREATE_SUSPENDED |          // Suspend so we can add to job
              Kernel32.CREATE_BREAKAWAY_FROM_JOB   // Allow ourselves to breakaway from Vista's PCA if necessary
      );

      boolean success = Kernel32.INSTANCE.CreateProcessW(null,
          commandLineStringBuilder.toString().toCharArray(),
          null,
          null,
          true,
          creationFlags,
          createLpEnv(env),
          shortenedPath(workingDir.getAbsolutePath()),
          startupInfo,
          processInformation);
      if (!success) {
        throw new IOException("Error calling CreateProcessW : " + Kernel32.INSTANCE.GetLastError());
      }

      if (!Kernel32.INSTANCE.AssignProcessToJobObject(hJob, processInformation.hProcess)) {
        throw new IOException("Cannot assign process to job : " + Kernel32.INSTANCE.GetLastError());
      }

      if (Kernel32.INSTANCE.ResumeThread(processInformation.hThread) <= 0) {
        throw new IOException("Cannot resume thread : " + Kernel32.INSTANCE.GetLastError());
      }

      if (!Kernel32.INSTANCE.CloseHandle(processInformation.hThread)) {
        throw new IOException("Error CloseHandle hThread : " + Kernel32.INSTANCE.GetLastError());
      }

      if (!Kernel32.INSTANCE.CloseHandle(hChildStd_IN_Rd)) {
        throw new IOException("Error CloseHandle IN_Rd : " + Kernel32.INSTANCE.GetLastError());
      }
      if (!Kernel32.INSTANCE.CloseHandle(hChildStd_OUT_Wr)) {
        throw new IOException("Error CloseHandle OUT_Wr : " + Kernel32.INSTANCE.GetLastError());
      }
      if (!Kernel32.INSTANCE.CloseHandle(hChildStd_ERR_Wr)) {
        throw new IOException("Error CloseHandle ERR_Wr : " + Kernel32.INSTANCE.GetLastError());
      }

      this.inputStream = new Win32PipeInputStream(hChildStd_OUT_Rd);
      this.outputStream = new Win32PipeOutputStream(hChildStd_IN_Wr);
      this.errorStream = new Win32PipeInputStream(hChildStd_ERR_Rd);
      this.processInfo = processInformation;

      this.running = true;
    }

    private static final String LONG_PATH_UNC_PREFIX = "\\\\?\\";

    private String shortenedPath(String absolutePath) throws IOException {
      char[] buffer = new char[1024];
      int copied = Kernel32.INSTANCE.GetShortPathName(LONG_PATH_UNC_PREFIX + absolutePath, buffer, buffer.length);
      if (copied < 1) {
        throw new IOException("GetShortPathName error : " + Kernel32.INSTANCE.GetLastError());
      }

      String shortenedAbsolutePath = new String(buffer, 0, copied);
      if (shortenedAbsolutePath.startsWith(LONG_PATH_UNC_PREFIX)) {
        shortenedAbsolutePath = shortenedAbsolutePath.substring(LONG_PATH_UNC_PREFIX.length());
      }
      return shortenedAbsolutePath;
    }

    private Pointer createLpEnv(Map<String, String> env) {
      String environmentBlock = Advapi32Util.getEnvironmentBlock(env);
      byte[] data = Native.toByteArray(environmentBlock);
      Pointer pointer = new Memory(data.length);
      pointer.write(0, data, 0, data.length);
      return pointer;
    }

    @Override
    public void destroy() {
      if (!this.running) {
        return;
      }
      this.running = false;

      if (!Kernel32.INSTANCE.TerminateJobObject(hJob, TERMINATE_EXIT_CODE)) {
        throw new RuntimeException("Error calling TerminateJobObject : " + Kernel32.INSTANCE.GetLastError());
      }
      this.exitCode = TERMINATE_EXIT_CODE;
      if (!Kernel32.INSTANCE.CloseHandle(hJob)) {
        throw new RuntimeException("Error calling CloseHandle on hJob : " + Kernel32.INSTANCE.GetLastError());
      }
      if (!Kernel32.INSTANCE.CloseHandle(processInfo.hProcess)) {
        throw new RuntimeException("Error calling CloseHandle on hProcess : " + Kernel32.INSTANCE.GetLastError());
      }
    }

    @Override
    public InputStream getInputStream() {
      return inputStream;
    }

    @Override
    public String[] getCommand() {
      return command;
    }

    @Override
    public InputStream getErrorStream() {
      return errorStream;
    }

    @Override
    public OutputStream getOutputStream() {
      return outputStream;
    }

    @Override
    public int exitValue() {
      if (running) {
        // throw the same exception as java.lang.Process.exitValue()
        throw new IllegalThreadStateException("process has not exited");
      }
      return exitCode;
    }

    public int waitFor() throws InterruptedException {
      if (running) {
        while (true) {
          int rc = Kernel32.INSTANCE.WaitForSingleObject(processInfo.hProcess, 100);
          if (Thread.interrupted()) {
            throw new InterruptedException();
          }
          if (rc == WinBase.WAIT_FAILED) {
            throw new RuntimeException("Error calling WaitForSingleObject : " + Kernel32.INSTANCE.GetLastError());
          }
          if (rc == WinBase.WAIT_OBJECT_0) {
            break;
          }
        }

        IntByReference exitCodeIntRef = new IntByReference();
        Kernel32.INSTANCE.GetExitCodeProcess(processInfo.hProcess, exitCodeIntRef);
        this.exitCode = exitCodeIntRef.getValue();
        this.running = false;

        if (!Kernel32.INSTANCE.TerminateJobObject(hJob, TERMINATE_EXIT_CODE)) {
          throw new RuntimeException("Error calling TerminateJobObject : " + Kernel32.INSTANCE.GetLastError());
        }
        if (!Kernel32.INSTANCE.CloseHandle(hJob)) {
          throw new RuntimeException("Error calling CloseHandle on hJob : " + Kernel32.INSTANCE.GetLastError());
        }

        if (!Kernel32.INSTANCE.CloseHandle(processInfo.hProcess)) {
          throw new RuntimeException("Error calling CloseHandle on hProcess: " + Kernel32.INSTANCE.GetLastError());
        }

        if (!Kernel32.INSTANCE.CloseHandle(inputStream.handle)) {
          throw new RuntimeException("Error calling CloseHandle on inputStream: " + Kernel32.INSTANCE.GetLastError());
        }
        if (!Kernel32.INSTANCE.CloseHandle(outputStream.handle)) {
          throw new RuntimeException("Error calling CloseHandle on outputStream: " + Kernel32.INSTANCE.GetLastError());
        }
        if (!Kernel32.INSTANCE.CloseHandle(errorStream.handle)) {
          throw new RuntimeException("Error calling CloseHandle on errorStream: " + Kernel32.INSTANCE.GetLastError());
        }
      }
      return exitCode;
    }

    private static WinNT.HANDLE[] createPipe(boolean out) throws IOException {
      WinBase.SECURITY_ATTRIBUTES securityAttributes = new WinBase.SECURITY_ATTRIBUTES();
      securityAttributes.bInheritHandle = true;

      WinNT.HANDLEByReference hReadPipe = new WinNT.HANDLEByReference();
      WinNT.HANDLEByReference hWritePipe = new WinNT.HANDLEByReference();

      if (!Kernel32.INSTANCE.CreatePipe(hReadPipe, hWritePipe, securityAttributes, 0)) {
        throw new IOException("Error calling CreatePipe : " + Kernel32.INSTANCE.GetLastError());
      }

      WinNT.HANDLE handleToMarkAsNotInherited = out ? hReadPipe.getValue() : hWritePipe.getValue();
      if (!Kernel32.INSTANCE.SetHandleInformation(handleToMarkAsNotInherited, WinBase.HANDLE_FLAG_INHERIT, 0)) {
        throw new IOException("Error calling SetHandleInformation on created pipe : " + Kernel32.INSTANCE.GetLastError());
      }

      return new WinNT.HANDLE[]{hReadPipe.getValue(), hWritePipe.getValue()};
    }

    static class Win32PipeOutputStream extends OutputStream {
      private final WinNT.HANDLE handle;

      Win32PipeOutputStream(WinNT.HANDLE handle) {
        this.handle = handle;
      }

      public void write(int b) throws IOException {
        byte[] buffer = new byte[1];
        buffer[0] = (byte) b;
        IntByReference bytesWritten = new IntByReference();
        if (!Kernel32.INSTANCE.WriteFile(handle, buffer, buffer.length, bytesWritten, null)) {
          throw new IOException("Error calling WriteFile : " + Kernel32.INSTANCE.GetLastError());
        }
      }
    }

    static class Win32PipeInputStream extends InputStream {
      private final WinNT.HANDLE handle;

      Win32PipeInputStream(WinNT.HANDLE handle) {
        this.handle = handle;
      }

      public int read() throws IOException {
        byte[] buffer = new byte[1];
        IntByReference bytesRead = new IntByReference();
        if (!Kernel32.INSTANCE.ReadFile(handle, buffer, buffer.length, bytesRead, null)) {
          int err = Kernel32.INSTANCE.GetLastError();
          if (err == WinError.ERROR_BROKEN_PIPE) {
            return -1;
          }
          throw new IOException("Error calling ReadFile : " + err);
        }
        if (bytesRead.getValue() == 0) {
          return -1;
        }
        return buffer[0];
      }
    }

  }

}
