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

import static java.lang.annotation.ElementType.METHOD;
import java.lang.annotation.Retention;
import static java.lang.annotation.RetentionPolicy.CLASS;
import java.lang.annotation.Target;

/**
 * Inclusive range of possible values of an integral value, which can be
 * annotated on a method of an AtomicState to indicate that a smaller number of
 * bits can be used for the value than the total required by its return type.
 * <p>
 * Note that values here are inclusive, so the maximum means up-to-and-including
 * (otherwise it would be impossible to specify, say, Long.MAX_VALUE as a
 * maximum, but only Long.MAX_VALUE-1.
 * </p>
 *
 * @author Tim Boudreau
 */
@Target(METHOD)
@Retention(CLASS)
public @interface ValueRange {

    /**
     * The minimum value, inclusive.
     *
     * @return A minimum
     */
    long minimum() default Long.MAX_VALUE; // intentional

    /**
     * The maximum value, inclusive.
     *
     * @return The maximum value
     */
    long maximum() default Long.MIN_VALUE;
}
