package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class UtilsTest {

    @Test
    void isEmpty() {
        assertTrue(Utils.isEmpty(null));
        assertTrue(Utils.isEmpty(""));
        assertTrue(Utils.isEmpty("   "));
        assertFalse(Utils.isEmpty("a"));
    }

    @Test
    void isNumeric() {
        assertTrue(Utils.isNumeric("123"));
        assertFalse(Utils.isNumeric("12a"));
        assertFalse(Utils.isNumeric(""));
    }

    @Test
    void capitalize() {
        assertEquals("Workshop", Utils.capitalize("WORKSHOP"));
    }

    // Averages are shown with one decimal, cut off (CFP-61)
    @Test
    void roundsRatingToOneDecimal() {
        assertEquals(4.2, Utils.roundRating(4.25));
        assertEquals(3.6, Utils.roundRating(3.6666));
        assertEquals(4.5, Utils.roundRating(4.5));
    }

    @Disabled("flaky on Jenkins")
    @Test
    void nowIsCurrentTime() {
        SimpleDateFormat f = new SimpleDateFormat(Utils.DB_DATE_TIME);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        assertEquals(f.format(new Date()), Utils.now());
    }

    @Test
    void acceptsRealEmails() {
        assertTrue(Utils.isValidEmail("ada@example.com"));
        assertTrue(Utils.isValidEmail("ADA@Example.com"));
        assertTrue(Utils.isValidEmail("ada.lovelace+cfp@mail.example.co.uk"));
    }

    @Test
    void rejectsWhatIsNotAnEmail() {
        for (String s : new String[] {null, "", "ada", "ada@", "@example.com", "ada@example", "ada@.com",
                "ada@example.", "ada@example..com", "ada lovelace@example.com", "ada@@example.com", " ada@example.com"}) {
            assertFalse(Utils.isValidEmail(s), String.valueOf(s));
        }
    }

    @Test
    void usShortIsTheLegacyReviewerFormatWithAnOrdinarySpace() {
        assertEquals("11/4/26, 1:05 PM", Utils.usShort(utc(2026, 11, 4, 13, 5)));
        assertEquals("11/4/26, 12:00 AM", Utils.usShort(utc(2026, 11, 4, 0, 0)));
        assertEquals("1/9/27, 9:30 AM", Utils.usShort(utc(2027, 1, 9, 9, 30)));
    }

    private static Date utc(int y, int mo, int d, int h, int mi) {
        return Date.from(java.time.ZonedDateTime.of(y, mo, d, h, mi, 0, 0, java.time.ZoneOffset.UTC).toInstant());
    }

    @Test
    void legacyTimestampsAreUtcWithMillisAndPlusZeroOffset() {
        long millis = utc(2026, 11, 4, 23, 30).getTime() + 5_007;  // 23:30:05.007 UTC, already the 5th in Oslo
        assertEquals("2026-11-04T23:30:05.007+00:00", Utils.legacyTimestamp(millis));
        assertEquals("2026-11-04", Utils.legacyDate(millis));
        assertEquals("2026-11-05T00:00:00.000+00:00", Utils.legacyTimestamp(utc(2026, 11, 5, 0, 0).getTime()));
    }
}
