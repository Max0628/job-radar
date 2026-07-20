package dev.jobradar.worker.normalizer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ContentHash {

    private ContentHash() {
    }

    public static String of(NormalizedJob job) {
        String material = String.join("|",
                nullToEmpty(job.title()),
                nullToEmpty(job.company()),
                String.valueOf(job.salaryMin()),
                String.valueOf(job.salaryMax()),
                nullToEmpty(job.description()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
