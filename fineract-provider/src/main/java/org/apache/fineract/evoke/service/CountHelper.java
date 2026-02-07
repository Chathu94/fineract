package org.apache.fineract.evoke.service;

import static java.lang.String.format;
public class CountHelper {
    public static String countQueryResult(final String sql) {
        // Needs to remove the limit and offset
        String qsql = sql
                .replaceAll("LIMIT \\d+", "")
                .replaceAll("limit \\d+", "")
                .replaceAll("OFFSET \\d+", "")
                .replaceAll("offset \\d+", "")
                .trim();
        return format("SELECT COUNT(*) FROM (%s) AS temp", qsql);
    }
}
