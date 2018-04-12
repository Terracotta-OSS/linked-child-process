package com.tc.lcp;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;

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

    private InputStream processIs;
    private OutputStream processOs;
    private InputStream processEs;
    private WinBase.PROCESS_INFORMATION.ByReference processInfo;

    private String[] command;
    private int exitCode = -1;
    private boolean running;

    Win32ProcessExecutor(String[] command, Map<String, String> env, File workingDir) throws IOException {
      this.command = command;
      StringBuilder commandLineStringBuilder = new StringBuilder();
      for (String c : command) {
        commandLineStringBuilder.append(c).append(" ");
      }

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

      WinBase.STARTUPINFO startupInfo = new WinBase.STARTUPINFO();
      startupInfo.hStdInput = hChildStd_IN_Rd;
      startupInfo.hStdOutput = hChildStd_OUT_Wr;
      startupInfo.hStdError = hChildStd_ERR_Wr;
      startupInfo.dwFlags |= WinBase.STARTF_USESTDHANDLES;
      WinBase.PROCESS_INFORMATION.ByReference processInformation = new WinBase.PROCESS_INFORMATION.ByReference();

      boolean success = Kernel32.INSTANCE.CreateProcessW(null,
          commandLineStringBuilder.toString().toCharArray(),
          null,
          null,
          true,
          new WinDef.DWORD(0),
          createLpEnv(env),
          workingDir.getAbsolutePath(),
          startupInfo,
          processInformation);
      if (!success) {
        throw new IOException("Error executing CreateProcessW");
      }

      if (!Kernel32.INSTANCE.CloseHandle(hChildStd_IN_Rd)) {
        throw new IOException("Error CloseHandle IN_Rd");
      }
      if (!Kernel32.INSTANCE.CloseHandle(hChildStd_OUT_Wr)) {
        throw new IOException("Error CloseHandle OUT_Wr");
      }
      if (!Kernel32.INSTANCE.CloseHandle(hChildStd_ERR_Wr)) {
        throw new IOException("Error CloseHandle ERR_Wr");
      }

      this.processIs = new WinInputStream(hChildStd_OUT_Rd);
      this.processOs = new WinOutputStream(hChildStd_IN_Wr);
      this.processEs = new WinInputStream(hChildStd_ERR_Rd);
      this.processInfo = processInformation;

      this.running = true;
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
      //XXX is exit code 1 okay?
      if (!Kernel32.INSTANCE.TerminateProcess(processInfo.hProcess, 1)) {
        throw new RuntimeException("Error executing TerminateProcess");
      }
    }

    @Override
    public InputStream getInputStream() {
      return processIs;
    }

    @Override
    public String[] getCommand() {
      return command;
    }

    @Override
    public InputStream getErrorStream() {
      return processEs;
    }

    @Override
    public OutputStream getOutputStream() {
      return processOs;
    }

    @Override
    public int exitValue() {
      if (running) {
        throw new IllegalThreadStateException("process has not exited");
      }
      return exitCode;
    }

    public int waitFor() throws InterruptedException {
      if (running) {
        Kernel32.INSTANCE.WaitForSingleObject(processInfo.hProcess, WinBase.INFINITE);
        IntByReference rc = new IntByReference();
        Kernel32.INSTANCE.GetExitCodeProcess(processInfo.hProcess, rc);
        this.exitCode = rc.getValue();

        Kernel32.INSTANCE.CloseHandle(processInfo.hProcess);
        Kernel32.INSTANCE.CloseHandle(processInfo.hThread);

        Kernel32.INSTANCE.CloseHandle(((WinInputStream) processIs).handle);
        Kernel32.INSTANCE.CloseHandle(((WinInputStream) processEs).handle);
        Kernel32.INSTANCE.CloseHandle(((WinOutputStream) processOs).handle);

        this.running = false;
      }
      return exitCode;
    }


    private static WinNT.HANDLE[] createPipe(boolean out) throws IOException {
      WinBase.SECURITY_ATTRIBUTES securityAttributes = new WinBase.SECURITY_ATTRIBUTES();
      securityAttributes.bInheritHandle = true;

      WinNT.HANDLEByReference hReadPipe = new WinNT.HANDLEByReference();
      WinNT.HANDLEByReference hWritePipe = new WinNT.HANDLEByReference();

      if (!Kernel32.INSTANCE.CreatePipe(hReadPipe, hWritePipe, securityAttributes, 0)) {
        throw new IOException("Error executing CreatePipe");
      }

      WinNT.HANDLE handleToMarkAsNotInherited = out ? hReadPipe.getValue() : hWritePipe.getValue();
      if (!Kernel32.INSTANCE.SetHandleInformation(handleToMarkAsNotInherited, WinBase.HANDLE_FLAG_INHERIT, 0)) {
        throw new IOException("Error executing SetHandleInformation");
      }

      return new WinNT.HANDLE[]{hReadPipe.getValue(), hWritePipe.getValue()};
    }

    static class WinOutputStream extends OutputStream {
      private final WinNT.HANDLE handle;

      WinOutputStream(WinNT.HANDLE handle) {
        this.handle = handle;
      }

      public void write(int b) throws IOException {
        byte[] buffer = new byte[1];
        buffer[0] = (byte) b;
        IntByReference bytesWritten = new IntByReference();
        if (!Kernel32.INSTANCE.WriteFile(handle, buffer, buffer.length, bytesWritten, null)) {
          int err = Kernel32.INSTANCE.GetLastError();
          throw new IOException("Error executing WriteFile (WinError=" + err + ")");
        }
      }
    }

    static class WinInputStream extends InputStream {
      private final WinNT.HANDLE handle;

      WinInputStream(WinNT.HANDLE handle) {
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
          throw new IOException("Error executing ReadFile: " + err);
        }
        if (bytesRead.getValue() == 0) {
          return -1;
        }
        return buffer[0];
      }
    }

  }

}
