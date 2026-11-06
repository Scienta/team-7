package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/** The shown average of specs/ratings-cr.md: trimmed from 5 ratings on, 2 decimals, banker's rounding. */
class RatingAverageTest {

    private static Double avg(int... scores) {
        return TalkDao.average(IntStream.of(scores).sum(), scores.length,
                scores.length == 0 ? 0 : IntStream.of(scores).min().getAsInt(),
                scores.length == 0 ? 0 : IntStream.of(scores).max().getAsInt());
    }

    @Test
    void nobodyRatedHasNoAverage() {
        assertNull(avg());
    }

    @Test
    void underFiveRatingsEveryScoreCounts() {
        assertEquals(4.25, avg(4, 4, 4, 5));
        assertEquals(4.33, avg(4, 5, 4));
        assertEquals(1.0, avg(1));
        assertEquals(3.0, avg(1, 5));
    }

    @Test
    void fromFiveRatingsExactlyOneHighestAndOneLowestAreLeftOut() {
        assertEquals(3.0, avg(1, 3, 3, 3, 5));           // (3+3+3)/3
        assertEquals(5.0, avg(5, 5, 5, 5, 5));           // only one of the 5s goes each way
        assertEquals(3.67, avg(1, 1, 5, 5, 5));          // (1+5+5)/3 = 3.666…
        assertEquals(2.33, avg(1, 1, 1, 5, 5));          // (1+1+5)/3 = 2.333…
        assertEquals(3.5, avg(1, 2, 3, 4, 5, 5));        // (2+3+4+5)/4
    }

    @Test
    void tiesAtTheThirdDecimalGoToTheEvenNeighbour() {
        // 25 / 8 = 3.125 -> 3.12 and 27 / 8 = 3.375 -> 3.38: eight counted scores, so ten ratings with 1 and 5 trimmed
        assertEquals(3.12, avg(1, 5, 5, 5, 3, 3, 3, 2, 2, 2));
        assertEquals(3.38, avg(1, 5, 5, 5, 4, 3, 3, 3, 2, 2));
        assertEquals(4.62, avg(1, 5, 5, 5, 5, 5, 5, 4, 4, 4)); // 37 / 8 = 4.625 -> 4.62
    }
}
