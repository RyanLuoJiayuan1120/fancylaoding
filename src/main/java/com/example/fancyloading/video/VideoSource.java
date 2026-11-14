package com.example.fancyloading.video;

import java.nio.ByteBuffer;

/** A decoded RGBA video source; only the newest frame is kept. */
public interface VideoSource extends AutoCloseable {

    /** Latest frame as RGBA, or null if the consumer already took it. */
    ByteBuffer takeFrame();

    boolean hasFirstFrame();

    String error();

    int width();

    int height();

    @Override
    void close();
}
