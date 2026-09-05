package com.hundredrabbits.orcac;

final class OrcaNative {
    static {
        System.loadLibrary("orcacore");
    }

    private OrcaNative() {
    }

    static native String run(String source, int ticks, int seed);
    static native long create();
    static native void destroy(long handle);
    static native void load(long handle, String source, int seed);
    static native String step(long handle, int ticks);
    static native String getGrid(long handle);
    static native String getEvents(long handle);
    static native String getEventsWire(long handle);
    static native long getTick(long handle);
}
