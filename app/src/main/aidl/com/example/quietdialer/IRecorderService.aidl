package com.example.quietdialer;

import android.os.ParcelFileDescriptor;

/** Runs inside Shizuku's shell process, which is allowed to record the call audio. */
interface IRecorderService {
    /** Shizuku requires this exact transaction id for destroy(). */
    void destroy() = 16777114;

    /** Starts recording into [out]. Returns "OK <source>" or an error text. */
    String start(in ParcelFileDescriptor out) = 1;

    /** Stops recording. Returns true when a file was written. */
    boolean stop() = 2;
}
