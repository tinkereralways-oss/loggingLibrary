package com.tracing.core.id;

import java.util.concurrent.ThreadLocalRandom;

public final class UlidGenerator implements TraceIdGenerator {

    private static final char[] ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    @Override
    public String generate() {
        long timestamp = System.currentTimeMillis();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long randomHigh = random.nextLong() & 0xFFFFFFFFFFFFL; // 48 bits
        long randomLow = random.nextInt() & 0xFFFFFFFFL;         // 32 bits unsigned

        char[] chars = new char[26];

        // Encode 48-bit timestamp (10 characters)
        chars[0] = ENCODING[(int) ((timestamp >>> 45) & 0x1F)];
        chars[1] = ENCODING[(int) ((timestamp >>> 40) & 0x1F)];
        chars[2] = ENCODING[(int) ((timestamp >>> 35) & 0x1F)];
        chars[3] = ENCODING[(int) ((timestamp >>> 30) & 0x1F)];
        chars[4] = ENCODING[(int) ((timestamp >>> 25) & 0x1F)];
        chars[5] = ENCODING[(int) ((timestamp >>> 20) & 0x1F)];
        chars[6] = ENCODING[(int) ((timestamp >>> 15) & 0x1F)];
        chars[7] = ENCODING[(int) ((timestamp >>> 10) & 0x1F)];
        chars[8] = ENCODING[(int) ((timestamp >>> 5) & 0x1F)];
        chars[9] = ENCODING[(int) (timestamp & 0x1F)];

        // Encode 80-bit randomness (16 characters)
        chars[10] = ENCODING[(int) ((randomHigh >>> 43) & 0x1F)];
        chars[11] = ENCODING[(int) ((randomHigh >>> 38) & 0x1F)];
        chars[12] = ENCODING[(int) ((randomHigh >>> 33) & 0x1F)];
        chars[13] = ENCODING[(int) ((randomHigh >>> 28) & 0x1F)];
        chars[14] = ENCODING[(int) ((randomHigh >>> 23) & 0x1F)];
        chars[15] = ENCODING[(int) ((randomHigh >>> 18) & 0x1F)];
        chars[16] = ENCODING[(int) ((randomHigh >>> 13) & 0x1F)];
        chars[17] = ENCODING[(int) ((randomHigh >>> 8) & 0x1F)];
        chars[18] = ENCODING[(int) ((randomHigh >>> 3) & 0x1F)];
        chars[19] = ENCODING[(int) (((randomHigh & 0x07) << 2) | ((randomLow >>> 30) & 0x03))];
        chars[20] = ENCODING[(int) ((randomLow >>> 25) & 0x1F)];
        chars[21] = ENCODING[(int) ((randomLow >>> 20) & 0x1F)];
        chars[22] = ENCODING[(int) ((randomLow >>> 15) & 0x1F)];
        chars[23] = ENCODING[(int) ((randomLow >>> 10) & 0x1F)];
        chars[24] = ENCODING[(int) ((randomLow >>> 5) & 0x1F)];
        chars[25] = ENCODING[(int) (randomLow & 0x1F)];

        return new String(chars);
    }
}
