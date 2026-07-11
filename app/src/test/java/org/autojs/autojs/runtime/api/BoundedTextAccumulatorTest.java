package org.autojs.autojs.runtime.api;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BoundedTextAccumulatorTest {

    @Test
    public void capsSingleLargeChunkAndReportsTruncation() {
        BoundedTextAccumulator accumulator = new BoundedTextAccumulator(4);

        accumulator.append("abcdefgh", 0, 8);
        BoundedTextAccumulator.Snapshot snapshot = accumulator.consume();

        assertEquals("abcd", snapshot.text);
        assertTrue(snapshot.truncated);
    }

    @Test
    public void capsAcrossChunksAndResetsAfterConsume() {
        BoundedTextAccumulator accumulator = new BoundedTextAccumulator(5);
        accumulator.append("abc", 0, 3);
        accumulator.append("def", 0, 3);

        BoundedTextAccumulator.Snapshot first = accumulator.consume();
        assertEquals("abcde", first.text);
        assertTrue(first.truncated);

        accumulator.append("xy", 0, 2);
        BoundedTextAccumulator.Snapshot second = accumulator.consume();
        assertEquals("xy", second.text);
        assertFalse(second.truncated);
    }

    @Test
    public void detectsSuffixAcrossChunks() {
        BoundedTextAccumulator accumulator = new BoundedTextAccumulator(9);
        accumulator.append("result $", 0, 8);
        accumulator.append(" ", 0, 1);

        assertTrue(accumulator.endsWith(" $ "));
    }

    @Test
    public void detectsSuffixAcrossChunksAfterContentIsTruncated() {
        BoundedTextAccumulator accumulator = new BoundedTextAccumulator(4);
        accumulator.append("long output $", 0, 13);
        accumulator.append(" ", 0, 1);

        assertTrue(accumulator.isTruncated());
        assertTrue(accumulator.endsWith(" $ "));
    }
}
