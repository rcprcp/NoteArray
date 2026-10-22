package com.notearray.service;

import jakarta.ws.rs.core.Response;
import java.util.Locale;

/**
 * Storage quota limits per subscription plan tier ('free', 'pro', 'premium')
 * and per-attachment size cap.
 */
public final class StorageQuota {

    public static final long FREE_LIMIT_BYTES = 100L * 1024 * 1024;           // 100 MB
    public static final long PRO_LIMIT_BYTES = 1L * 1024 * 1024 * 1024;       // 1 GB
    public static final long PREMIUM_LIMIT_BYTES = 5L * 1024 * 1024 * 1024;   // 5 GB
    public static final int MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024;          // 10 MB per file

    private StorageQuota() {
    }

    public static long limitBytesForPlan(String plan) {
        if (plan == null) {
            return FREE_LIMIT_BYTES;
        }
        return switch (plan.trim().toLowerCase(Locale.ROOT)) {
            case "pro" -> PRO_LIMIT_BYTES;
            case "premium" -> PREMIUM_LIMIT_BYTES;
            default -> FREE_LIMIT_BYTES;
        };
    }

    public static boolean isOverQuota(long currentUsedBytes, String plan) {
        return currentUsedBytes >= limitBytesForPlan(plan);
    }

    /**
     * Guard for creating a new note, importing notes, or uploading an attachment:
     * rejects if the user is already at/above their plan limit (even for a 0-byte
     * note) or if adding {@code newBytes} would exceed the limit.
     */
    public static Response checkCanCreate(long currentUsedBytes, long newBytes, String plan) {
        long limit = limitBytesForPlan(plan);
        long added = Math.max(0L, newBytes);
        if (currentUsedBytes >= limit || currentUsedBytes + added > limit) {
            return quotaExceededResponse(plan);
        }
        return null;
    }

    /**
     * Guard for updating an existing note: allows shrinking or keeping the same
     * size even when over quota so the user can recover space, but blocks
     * increasing storage beyond the plan limit.
     */
    public static Response checkQuota(long currentUsedBytes, long deltaBytes, String plan) {
        if (deltaBytes <= 0) {
            return null;
        }
        long limit = limitBytesForPlan(plan);
        if (currentUsedBytes + deltaBytes > limit) {
            return quotaExceededResponse(plan);
        }
        return null;
    }

    private static Response quotaExceededResponse(String plan) {
        // Plan-agnostic wording: this guard runs for every tier (free/pro/premium) and the
        // caller's plan is already visible in the UI. The specific bumped limit for an
        // import is surfaced by the import flow itself.
        return Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE)
                .entity("Storage quota exceeded. Please upgrade your plan or delete notes to free up space.")
                .build();
    }

    /**
     * Human-readable limit for a plan ("100 MB" / "1 GB" / "5 GB"), used in
     * messages that should show exactly what the caller's plan allows.
     */
    public static String limitLabelForPlan(String plan) {
        long bytes = limitBytesForPlan(plan);
        if (bytes >= 1024L * 1024 * 1024) {
            return (bytes / (1024L * 1024 * 1024)) + " GB";
        }
        return (bytes / (1024L * 1024)) + " MB";
    }
}
