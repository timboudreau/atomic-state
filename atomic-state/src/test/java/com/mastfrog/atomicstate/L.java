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

import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assertions;

/**
 * If this class directly implements StatelyStateHolder, the build will fail; so
 * the test accesses it via a lambda.
 *
 * @author Tim Boudreau
 */
class L<T> {

    private T prev;
    private T nue;

    void assertChange(BiConsumer<T, T> st) {
        T prevState = prev;
        T nextState = nue;
        prev = nue = null;
        Assertions.assertNotNull(prevState, "Prev state is null");
        Assertions.assertNotNull(nextState, "Next state is null");
        st.accept(prevState, nextState);
    }

    public void onChange(T previousState, T changedToState,
            Supplier<T> currentState) {
        prev = previousState;
        nue = changedToState;
        Assertions.assertEquals(changedToState, currentState.get());
    }

}
