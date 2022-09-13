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

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AtomicStateTest {

    @Test
    public void testState() {
        StatelyState st = StatelyState.INITIAL;
        assertEquals(0, st.age(), st::toString);
        assertEquals(0, st.number(), st::toString);

        assertFalse(st.isCool());
        StatelyState st2 = st.withIsCool(true);

        assertTrue(st2.isCool(), st2::toString);

        StatelyState st3 = st2.withAge(32);
        assertTrue(st3.isCool(), st3::toString);
        assertEquals(32, st3.age(), st3::toString);

        StatelyState st4 = st3.withNumber(3);
        assertEquals(3, st4.number(), st4::toString);
        assertEquals(32, st4.age(), st4::toString);
        assertTrue(st4.isCool(), st4::toString);

        StatelyState st5 = st4.withThing(Things.FEATHER_BARBULES);
        assertEquals(3, st5.number(), st5::toString);
        assertEquals(32, st5.age(), st5::toString);
        assertTrue(st5.isCool(), st5::toString);
        assertSame(Things.FEATHER_BARBULES, st5.thing(), st5::toString);

        for (int i = 0; i < Things.values().length; i++) {
            Things t = Things.values()[i];
            StatelyState st6 = st5.withThing(t);
            assertSame(t, st6.thing(), st6::toString);
            assertEquals(3, st6.number(), st6::toString);
            assertEquals(32, st6.age(), st6::toString);
            assertTrue(st6.isCool(), st6::toString);
        }
    }

    @Test
    public void testHolder() {
        L<StatelyState> l = new L<>();

        StatelyStateHolder hld = new StatelyStateHolder((a, b, c) -> {
            l.onChange(a, b, c);
        });
        StatelyState next = hld.updateAndGet(old
                -> old.withAge(23).withIsCool(true)
                        .withThing(Things.DARTH_VADER)
        );
        assertSame(next.thing(), Things.DARTH_VADER, next::toString);
        assertEquals(23, next.age(), next::toString);
        assertTrue(next.isCool(), next::toString);

        l.assertChange((old, nue) -> {
            assertSame(nue.thing(), Things.DARTH_VADER, nue::toString);
            assertEquals(23, nue.age(), nue::toString);
            assertTrue(nue.isCool(), nue::toString);
            assertSame(old.thing(), Things.SHOES, old::toString);
            assertFalse(old.isCool(), nue::toString);
            assertEquals(0, old.age(), old::toString);
        });

        StatelyState gau = hld.getAndUpdate(old -> old.withThing(Things.CLOUDS));
        assertSame(Things.DARTH_VADER, gau.thing(), gau::toString);
        l.assertChange((old, nue) -> {
            assertSame(Things.DARTH_VADER, old.thing(), old::toString);
            assertSame(Things.CLOUDS, nue.thing(), nue::toString);
        });
    }
}
