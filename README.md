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
            <version>1.0.0</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>com.mastfrog.atomicstate</groupId>
            <artifactId>atomic-state-annotation-processor</artifactId>
            <version>1.0.0</version>
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


Conclusion
==========

This is definitely a tool to solve a somewhat esoteric problem, but it's one that, when you have it, is
fussy to implement and easy to make mistakes with.  So, if you want to do old-school, highly efficient
state management, and your state can be shoehorned into <= 64 bits, but have your code walk and talk like
modern object-oriented code, you can do that.

Could you do some of this with a lock and a hand-written state object?  Sure, but there is risk of deadlock.

Could you do it with `AtomicReference` and a hand-written state object?  Yes, but with more overhead (allocating
an `int` or `long` is about as cheap as it gets) and doing your coalescing by hand.

It's not for everything, or even most things, but if you have the problem it solves, it is really nice to have
a solution to it.

