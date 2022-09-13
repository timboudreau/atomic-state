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

public class TouchingMaxValuesTest {

    @Test
    public void test() {
        TouchingMaxValuesState st = TouchingMaxValuesState.INITIAL;
        assertEquals(65535, st.b());
        assertEquals(0, st.a());

        /*
    @ValueRange(minimum = 0, maximum = 65535)
    public int a();

    @ValueRange(minimum = 65535, maximum = 65535 * 2)
    public int b();
         */
        TouchingMaxValuesState nue = st.withA(32768);

        assertEquals(32768, nue.a());

        nue = nue.withB(65535 + 32768);

        assertEquals(65535 + 32768, nue.b());

        try {
            TouchingMaxValuesState test = st.withA(-1);
            fail("Exception should have been thrown: " + test);
        } catch (Exception e) {

        }

        try {
            TouchingMaxValuesState test = st.withA(70000);
            fail("Exception should have been thrown: " + test);
        } catch (Exception e) {

        }

        try {
            TouchingMaxValuesState test = st.withB(1);
            fail("Exception should have been thrown: " + test);
        } catch (Exception e) {

        }

        try {
            TouchingMaxValuesState test = st.withB(65535 + 65535 + 10);
            fail("Exception should have been thrown: " + test);
        } catch (Exception e) {

        }

    }
}
