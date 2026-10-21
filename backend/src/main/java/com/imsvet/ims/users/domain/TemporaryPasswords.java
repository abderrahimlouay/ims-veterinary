package com.imsvet.ims.users.domain;

import java.security.SecureRandom;

/**
 * ADR-045: temporary passwords of 16 random characters without look-alikes (no 0/o, 1/l/i), written in four
 * groups ({@code k7mq-3xht-9pwd-r2va}), about 79 bits of entropy; easy to read out at the counter.
 */
public final class TemporaryPasswords {

    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private TemporaryPasswords() {
    }

    public static String generate() {
        StringBuilder password = new StringBuilder(19);
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) {
                password.append('-');
            }
            password.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return password.toString();
    }
}
