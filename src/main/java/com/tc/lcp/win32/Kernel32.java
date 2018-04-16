/*
 * All content copyright (c) 2003-2007 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.lcp.win32;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.W32APIOptions;

import java.util.Arrays;
import java.util.List;

public interface Kernel32 extends com.sun.jna.platform.win32.Kernel32 {

  Kernel32 INSTANCE = (Kernel32) Native.loadLibrary(
      "kernel32", Kernel32.class, W32APIOptions.UNICODE_OPTIONS);

  // 0x00000800
  int JOB_OBJECT_LIMIT_BREAKAWAY_OK = 2048;
  // 0x00002000
  int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 8192;
  // 0x00000020
  int JOB_OBJECT_UILIMIT_GLOBALATOMS = 0x00000020;
  // see SetInformationJobObject at msdn
  int JobObjectExtendedLimitInformation = 9;
  // see SetInformationJobObject at msdn
  int JobObjectBasicUIRestrictions = 4;

  class JOBJECT_BASIC_LIMIT_INFORMATION extends Structure {
    public LARGE_INTEGER PerProcessUserTimeLimit;
    public LARGE_INTEGER PerJobUserTimeLimit;
    public int LimitFlags;
    public SIZE_T MinimumWorkingSetSize;
    public SIZE_T MaximumWorkingSetSize;
    public int ActiveProcessLimit;
    public ULONG_PTR Affinity;
    public int PriorityClass;
    public int SchedulingClass;

    @Override
    protected List<String> getFieldOrder() {
      return Arrays.asList("PerProcessUserTimeLimit", "PerJobUserTimeLimit", "LimitFlags", "MinimumWorkingSetSize",
          "MaximumWorkingSetSize", "ActiveProcessLimit", "Affinity", "PriorityClass", "SchedulingClass");
    }
  }

  class IO_COUNTERS extends Structure {
    public ULONGLONG ReadOperationCount;
    public ULONGLONG WriteOperationCount;
    public ULONGLONG OtherOperationCount;
    public ULONGLONG ReadTransferCount;
    public ULONGLONG WriteTransferCount;
    public ULONGLONG OtherTransferCount;

    @Override
    protected List<String> getFieldOrder() {
      return Arrays.asList("ReadOperationCount", "WriteOperationCount", "OtherOperationCount", "ReadTransferCount",
          "WriteTransferCount", "OtherTransferCount");
    }
  }

  class JOBJECT_EXTENDED_LIMIT_INFORMATION extends Structure {
    public JOBJECT_EXTENDED_LIMIT_INFORMATION() {}

    public JOBJECT_EXTENDED_LIMIT_INFORMATION(Pointer memory) {
      super(memory);
    }

    @Override
    protected List<String> getFieldOrder() {
      return Arrays.asList("BasicLimitInformation", "IoInfo", "ProcessMemoryLimit", "JobMemoryLimit",
          "PeakProcessMemoryUsed", "PeakJobMemoryUsed");
    }

    public JOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation;
    public IO_COUNTERS IoInfo;
    public SIZE_T ProcessMemoryLimit;
    public SIZE_T JobMemoryLimit;
    public SIZE_T PeakProcessMemoryUsed;
    public SIZE_T PeakJobMemoryUsed;

    public static class ByReference extends JOBJECT_EXTENDED_LIMIT_INFORMATION implements Structure.ByReference {
      public ByReference() {}

      public ByReference(Pointer memory) {
        super(memory);
      }
    }
  }

  class JOBOBJECT_BASIC_UI_RESTRICTIONS extends Structure {
    public JOBOBJECT_BASIC_UI_RESTRICTIONS() {}

    public JOBOBJECT_BASIC_UI_RESTRICTIONS(Pointer memory) {
      super(memory);
    }

    @Override
    protected List<String> getFieldOrder() {
      return Arrays.asList("UIRestrictionsClass");
    }

    public int UIRestrictionsClass;

    public static class ByReference extends JOBOBJECT_BASIC_UI_RESTRICTIONS implements Structure.ByReference {
      public ByReference() {}

      public ByReference(Pointer memory) {
        super(memory);
      }
    }
  }

  WinNT.HANDLE CreateJobObject(WinBase.SECURITY_ATTRIBUTES attrs, String name);
  boolean SetInformationJobObject(HANDLE hJob, int JobObjectInfoClass, Pointer lpJobObjectInfo, int cbJobObjectInfoLength);
  boolean AssignProcessToJobObject(HANDLE hJob, HANDLE hProcess);
  int ResumeThread(HANDLE hThread);
  boolean TerminateJobObject(HANDLE hJob, int uExitCode);

}
