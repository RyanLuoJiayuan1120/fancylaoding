package com.example.videoloading.early;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Captures the game's own log messages (formatted message text, unchanged) so the
 * loading screen can display the latest line above the progress bar.
 *
 * <p>This class deliberately uses only Log4j, no Minecraft classes: it is installed
 * from the Mixin plugin / preLaunch, before Minecraft is loadable.</p>
 */
public final class LoadingLog {

    private static final int MAX_LINES = 32;
    private static final Deque<String> LINES = new ArrayDeque<>();
    private static volatile long lastUpdateNanos;
    private static volatile boolean installed;

    private LoadingLog() {
    }

    public static void install() {
        if (installed) {
            return;
        }
        try {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            Configuration config = context.getConfiguration();
            AbstractAppender appender = new AbstractAppender(
                    "videoloading-log", null, null, false, Property.EMPTY_ARRAY) {
                @Override
                public void append(LogEvent event) {
                    try {
                        if (event.getLevel().intLevel() > Level.INFO.intLevel()) {
                            return;
                        }
                        String logger = event.getLoggerName();
                        if (logger != null && logger.startsWith("com.example.videoloading")) {
                            return;
                        }
                        String msg = event.getMessage() == null
                                ? null : event.getMessage().getFormattedMessage();
                        if (msg == null || msg.isBlank()) {
                            return;
                        }
                        push(msg);
                    } catch (Throwable ignored) {
                        // never let logging break the game
                    }
                }
            };
            appender.start();
            config.getRootLogger().addAppender(appender, Level.INFO, null);
            context.updateLoggers();
            installed = true;
            System.out.println("[videoloading] LoadingLog appender installed");
        } catch (Throwable t) {
            System.out.println("[videoloading] LoadingLog appender unavailable: " + t);
        }
    }

    public static void push(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        synchronized (LINES) {
            LINES.addLast(line);
            while (LINES.size() > MAX_LINES) {
                LINES.removeFirst();
            }
            lastUpdateNanos = System.nanoTime();
        }
    }

    /** Latest raw log line, or null if none has been seen yet. */
    public static String latest() {
        synchronized (LINES) {
            return LINES.peekLast();
        }
    }

    public static boolean isStale(long millis) {
        long last = lastUpdateNanos;
        return last == 0L || System.nanoTime() - last > millis * 1_000_000L;
    }

    public static int size() {
        synchronized (LINES) {
            return LINES.size();
        }
    }
}
