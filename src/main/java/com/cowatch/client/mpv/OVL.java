package com.cowatch.client.mpv;

import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinNT;
import java.util.Arrays;
import java.util.List;

/** Windows OVERLAPPED structure for JNA overlapped I/O. */
public class OVL extends Structure implements Structure.ByReference {
    public long         Internal     = 0;
    public long         InternalHigh = 0;
    public int          Offset       = 0;
    public int          OffsetHigh   = 0;
    public WinNT.HANDLE hEvent       = null;

    public OVL() { super(); }

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("Internal", "InternalHigh", "Offset", "OffsetHigh", "hEvent");
    }
}