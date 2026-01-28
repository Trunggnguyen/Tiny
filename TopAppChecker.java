package com.android.server.maxpower.chain;

/**
 * Must return current top foreground package for userId, or null if unknown.
 * Implement using ATMS/AMS internal state (preferred).
 */
public interface TopAppChecker {
    String getTopPackage(int userId);
}
