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

import static org.junit.jupiter.api.Assertions.assertSame;
import org.junit.jupiter.api.Test;

public class IntParameterOrderTest {

    @Test
    public void test() {
        IntParameterOrderState ord = IntParameterOrderState.INITIAL;
        System.out.println("BASE " + ord);

        IntParameterOrderState ord2 = ord.withBbbb(23).withEeee(Things.DARTH_VADER)
                .withGggg(true)
                .withIiii(Wuggles.LAMP);

        System.out.println("ORD2 " + ord2);

        assertSame(Things.DARTH_VADER, ord2.eeee(), ord2::toString);
        assertSame(Wuggles.LAMP, ord2.iiii(), ord2::toString);

        
        IntParameterOrderState ord3 = ord2.withIiii(Wuggles.BOOKSHELF);
        
        assertSame(Wuggles.BOOKSHELF, ord3.iiii());
        
    }
}
