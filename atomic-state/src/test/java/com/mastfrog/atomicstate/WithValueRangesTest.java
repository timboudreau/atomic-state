/*
 * The MIT License
 *
 * Copyright 2022 Mastfrog Technologies.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.mastfrog.atomicstate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.Test;

public class WithValueRangesTest {

    @Test
    public void test() {
        WithValueRangesState wvr = new WithValueRangesState(0);
        WithValueRangesState wvr2 = wvr.withAge(20).withLimbs(4)
                .withNegativity(-5);

        assertEquals(4, wvr2.limbs());
        assertEquals(20, wvr2.age());
        assertEquals(-5, wvr2.negativity());

        for (int i = 1; i <= 120; i++) {
            WithValueRangesState test = wvr.withAge(i);
            assertEquals(i, test.age(), test::toString);
        }
        for (int i = 0; i <= 4; i++) {
            WithValueRangesState test = wvr.withLimbs(i);
            assertEquals(i, test.limbs(), test::toString);
        }
        for (int i = -20; i <= 5; i++) {
            WithValueRangesState test = wvr.withNegativity(i);
            assertEquals(i, test.negativity(), test::toString);
        }

        try {
            wvr.withAge(200);
            fail("Exception should have been thrown by " + wvr);
        } catch (Exception e) {

        }
        try {
            wvr.withAge(-10);
            fail("Exception should have been thrown by " + wvr);
        } catch (Exception e) {

        }
        try {
            wvr.withLimbs(-1);
            fail("Exception should have been thrown by " + wvr);
        } catch (Exception e) {

        }
        try {
            wvr.withLimbs(-10);
            fail("Exception should have been thrown by " + wvr);
        } catch (Exception e) {

        }
        try {
            wvr.withLimbs(5);
            fail("Exception should have been thrown by " + wvr);
        } catch (Exception e) {

        }
        try {
            wvr.withLimbs(15);
            fail("Exception should have been thrown by " + wvr);
        } catch (Exception e) {

        }
        try {
            wvr.withNegativity(-21);
            fail("Exception should have been thrown by " + wvr);
        } catch (Exception e) {

        }
        try {
            wvr.withNegativity(6);
            fail("Exception should have been thrown by " + wvr);
        } catch (Exception e) {

        }

    }
}
