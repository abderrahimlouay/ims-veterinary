package com.imsvet.ims.common.db;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ADR-003 as amended: the application needs MySQL 8.0.30 or later ({@code REVOKE IF EXISTS} in the grants of
 * ADR-087; CHECK constraints enforced since 8.0.16); the supported release line is 8.4 LTS (ADR-032).
 */
public final class MySqlVersion {

    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)");

    private static final int[] MINIMUM = { 8, 0, 30 };

    private MySqlVersion() {
    }

    /** Fails with the minimum and the target when {@code version} (as {@code SELECT VERSION()}) is older or unknown. */
    public static void requireSupported(String version) {
        Matcher m = version == null ? null : VERSION.matcher(version);
        boolean supported = m != null && m.find() && !version.contains("MariaDB") && atLeastMinimum(
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        if (!supported) {
            throw new IllegalStateException("MySQL " + version + " is not supported: version 8.0.30 or later is required "
                    + "(ADR-003); use MySQL 8.4 LTS.");
        }
    }

    private static boolean atLeastMinimum(int major, int minor, int patch) {
        int[] found = { major, minor, patch };
        for (int i = 0; i < MINIMUM.length; i++) {
            if (found[i] != MINIMUM[i]) {
                return found[i] > MINIMUM[i];
            }
        }
        return true;
    }
}
