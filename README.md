Atomic State
============

Annotation and processors to solve an esoteric, but when-you-need-it-you-need-it
problem:  Packing complex state into a single primitive `int` or `long` and managing
state changes locklessly using `AtomicInteger` / `AtomicLong`.

I've used this pattern in a few different, pretty heterogenous, kinds of environments
where you have state that can be banged on by multiple threads, and you want to manage
that - and perhaps to respond to changes in it - without involving any locks.  Examples:

  * A wrapper around `NuProcess` API for launching processes, where you want to encapsulate
    * The state of the process (not-started, started, running...) - an enum
    * Whether the an attempt to kill the process was made
    * The process pid, once there is one
  * A desktop "tray" application that polls a bunch of web services, encapsulating a bunch of information to animate the tray icon, decide whether to do network I/O and more
    * Whether the menu is open (this comes from the AppKit thread on Mac OS)
    * Whether or not network I/O is underway
    * Whether or not a dialog is open
    * Whether or not the application is exiting
    * A relative, low resolution timestamp of the last poll time
  * Managing complex state for an HTTP client, composing together states like
    * What phase of handling the request the code is in (initial, headers-sent, body-sent,
      headers-received, receiving-body, body-received, done) along with
    * If the request is done, the reason (success, error-code-response, cancelled, timed-out, errored)

To do that, you simply do the fussy work of carving up an int or a long into some number of bits,
allocate one bit to booleans, however many bits it takes to hold the element count of enums, however
many bits you need for numbers, and so forth.  Fussy, but once it works, it works.

But this is the sort of rote logic that is easy to generate perfectly every time - so why not automate it?

How To Use It
=============

Create an interface which has methods named for the elements that compose your state.  Annotate it with `@AtomicState`.
The methods must return primitive types *only* (unless they have a default implementation, in which case they are
ignored).  E.g.

```java
@AtomicState(generateChangeSupport = true)
public interface Stately {

    public byte number();

    public boolean isCool();

    public Things thing(); // an enum

    public short age();

}
```

But actually, we can do better than this (nobody likes having to pass `(short) foo` to use `short`, but
you do want to use only as many bits as you need), using the `@ValueRange` annotation - so our methods
can return `int`, but we only use as many bits as is required to fit numbers in that range.  Not only
that, but we can have ranges that start and end where we want:

```java
@AtomicState(generateChangeSupport = true)
public interface Stately {

    @ValueRange(minimum=0, maximum=127)
    public int number(); // this will use 7 bits

    public boolean isCool(); // one bit

    public Things thing();

    @ValueRange(minimum=1, maximum=130)
    public int age();

}
```


if using Maven, you need to depend on `atomic-state` and `atomic-state-annotation-processor` - but you
can use `<scope>provided</scope>` - `@AtomicState` is class-retention only, and the annotation processor
is only needed at compile time - so, nothing to pollute your classpath gets pulled in - you are left with
just the code you want to generate:


```xml
        <dependency>
            <groupId>com.mastfrog.atomicstate</groupId>
            <artifactId>atomic-state</artifactId>
            <version>1.0.1</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>com.mastfrog.atomicstate</groupId>
            <artifactId>atomic-state-annotation-processor</artifactId>
            <version>1.0.1</version>
            <scope>provided</scope>
        </dependency>

```

What You Get
============

When you build your project, you get an implementation of your interface suffixed with `State` - so,
for the example code, we get a `final` class called `StatelyState`.  It is an immutable wrapper
for an `int` or `long` (depending on how many bits were needed), with mutation methods that return a
new instance.  And all mutator and creation methods for numbers will take `int` even if you specified
`short` or `byte` in the interface, for ease of use, but do range checks - and if you used `@ValueRange`,
that range is enforced as well.  So, usage looks like

```java
StatelyState myState = StatelyState.INITIAL.withIsCool(true).withAge(23).withNumber(10)
```

The generated implementation of your state interface will have the following added methods:

 * `with*(value)` (where `*` is the capitalized name of the original method) methods which take a value of 
    of the type returned by the original method, which returns a new instance of the generated
    class with that value set to the passed value (or returns the same instance if the passed
    value is not different from the requested one)
 * `static from(YourInterface)` - takes an instance of your interface which might not be an instance of
    the generated type, and converts it to one if necessary
 * `equals(Object)`, `toString()` and `hashCode()` that work as one would expect
 * `toMap()` - returns a `Map<String,Object>` representing the contents of your object as key/value pairs
    (that can be useful if you're going to serialize an instance and reconsititute it much later in
    a JVM that may have a different version of your class with added or removed methods)
 * `static fromMap(Map<String, Object>)` which reverses what `toMap()` does, and tolerates
    unknown keys, strings for enums and/or `ints` where `longs` are expected and similar.
 * A factory method, `new$THE_GENERATED_TYPE_NAME` which takes either an `int` or `long` depending
   on the number of bits required.
    * Note, **all** constructed instances are validated on construction, and will throw an
      `IllegalArgumentException` if the input value, say, requests an enum constant greater than
      the number of enum constants in an enum type, or out of range with respect to any `@ValueRange`
      annotation - so it is impossible to create an instance that is in a nonsensical state.

The **other** thing you get is a `*StateHolder` class, which encapsulates an `AtomicInteger` or `AtomicLong`
and provides atomic methods `getAndUpdate(UnaryOperator<YourState>)`, `updateAndGet(UnaryOperator<YourState>)`
and `set(YourState)`.

If you included `generateListenerSupport=true` in your `@AtomicState` annotation, the state holder can be
listened on for changes using a generated `*StateListener` interface passed into its constructor, and the
state listener interface has built-in methods for asynchronous event publishing using your own executor
or `ForkJoinPool.commonPool`.  It will have a signature like


```
    void onChange(StatelyState previousState, StatelyState changedToState,
                Supplier<StatelyState> currentState);
```

Since this *is* lockless and atomic, depending on what you're doing, you may want to use the `Supplier` to
get the state right now (if, say, you're updating a UI element) or use `changedToState` (if, say, you're
logging every state transition).

For example, here is a usage where an atomic state implementation is being used to carefully manage
the reported state of a web service request, which has a `CompletableFuture` it needs to
notify with a JSON representation of a the response, once the request cycle has ended - this
is in an implementation of `HttpResponse.BodyHandler.onError` for the JDK's HTTP client - which
is an example of a case where you have multiple threads banging unpredictably on some state:

```
    @Override
    public void onError(Throwable throwable) {
        HttpOperationState prevState = state.getAndUpdate(old -> 
            old
                .withState(State.DONE)
                .withReason(CompletionReason.ERRORED)
        );
        // If the future was not completed, complete it now
        if (!prevState.futureCompleted()) {
            completeFuture(ServiceResult.thrown(throwable));
        }
    }
```

Limitiations
============

The return types of methods on the interface you annotate with `@AtomicState` **must**
be primitive types, and the total number of bits required must be less than or equal
to 64 (hint: use `@ValueRange` to reduce the number of bits needed if there is a
maximum practical value, you don't ever need to represent a negative number, etc.).

Enums consume as many bits as it takes to represent the highest ordinal - the count
of enum constants present.


Future Plans
============

  * Returning `long` is currently not supported, since that would consume all 64 bits
of an AtomicLong in one shot.  It ought to be allowed if `@ValueRange` is present and
the result would fit in less than 64 bits.
  * State implementation classes implement `Serializable`, but if the interface changes,
the result will be incompatible.  This could be improved by implementing `Externalizable`
directly or indirectly instead, and instead writing a `Map<String,Object>` with named values
to the output stream.  For now, use `toMap` and `fromMap` for this purpose.


Conclusion
==========

This is definitely a tool to solve a somewhat esoteric problem, but it's one that, when you have it, is
fussy to implement and easy to make mistakes with.  So, if you want to do old-school, highly efficient
state management, and your state can be shoehorned into <= 64 bits, but have your code walk and talk like
modern object-oriented code, this library lets you do that without the knotty task of implementing a
lot of bitwise logic.

Could you do some of this with a lock and a hand-written state object?  Sure, but there is risk of deadlock.

Could you do it with `AtomicReference` and a hand-written state object?  Yes, but with more overhead (allocating
an `int` or `long` is about as cheap as it gets) and doing your coalescing by hand.

It's not for everything, or even most things, but if you have the problem it solves, it is really nice to have
a solution to it.
