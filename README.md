# Libretto

Declarative concurrency and stream processing library for Scala.

In a nutshell, Libretto took inspiration from linear logic and added concurrent semantics of the multiplicative
conjunction, primitives for racing, and primitive operator for recursion.

## Design Goals

- **Expressiveness.** The term is used to mean either of two things:
  - a measure of what is possible to represent at all; or
  - a measure of how naturally things are represented.

  Libretto aims to be expressive in both these meanings. Things like dynamic stream topologies, feedback loops,
  or session types are readily expressible in Libretto, without them being primitives.

- **Declarativeness.**
 
  _“Progress is possible only if we train ourselves to think about programs
  without thinking of them as pieces of executable code.”_ -- Edsger W. Dijkstra

- **Strong static guarantees.** It is guaranteed at compile time that every producer is connected to a consumer.
  Typed protocols (session types) are checked at compile time for type safety.

- **Programs as data structures.** Libretto programs are just data structures.
  As such, they can be inspected, manipulated, optimized, given different interpretations, and possibly even serialized
  and sent over the wire.
  
## Why Another Stream Processing Library

Existing libraries that I know of compromise some or all of the above stated design goals.

Implementing custom dynamic dataflow topologies with existing libraries is either not possible at all or possible only
through escaping to low-level imperative world. On the other hand, Libretto has no imperative concepts like
pre-materialization, fibers or signalling variables, yet things like merge, broadcast, feedback loop or even
stream itself are not primitives, but are readily implemented on top of the expressive set of primitives.

Libretto strictly separates the (description of a) program from its execution.
In particular, there are no "blueprints" that are in fact already running.
Libretto programs are just pure values.
Moreover, unlike monad-based programs-as-values, Libretto programs are not hidden inside an inscrutable Scala function
after the first `flatMap`. This opens new possibilities for what can be done with a program (description).

## Documentation

### Scaladoc

**(TODO)**

Found something undocumented or not documented clearly? We should do better. Do not hesitate to
[submit a documentation request](https://github.com/TomasMikula/libretto/issues/new?labels=documentation).

### Tutorial

**(TODO)**

## Caveats

It is all too common for software projects to highlight only the good parts or to overstate their features.
We want to do better and be absolutely honest about the limitations. However, it is hard to know what we are missing.
That's why we need your help.

Do you find that libretto does not hold up to some of its promises?
Do you find that the project description omits some important limitation?
If you have invested significant time in libretto only to find out that it's not for you,
what information would have saved your time?
Please, [let us know](https://github.com/TomasMikula/libretto/issues/new?labels=criticism).

## FAQs

Did not find an answer to your question?
Do not hesitate to [ask us](https://github.com/TomasMikula/libretto/issues/new?labels=question).

**Is Libretto for me?**

Libretto is for anyone who needs to implement concurrent systems or stream processing in Scala.
You are more likely to appreciate Libretto if you:
 - cannot function without static types;
 - are into functional/declarative programming;
 - like when the compiler is guiding you (type-driven development);
 - hate falling back to imperative code (like the `IO` monad) when it comes to concurrency;
 - have hit an expressiveness limit of an existing library when you needed a slightly non-trivial data-flow topology.

**How can libretto _statically_ guarantee that each resource is consumed exactly once when Scala does not have linear type system features?**

**Why do I have to write libretto programs in that strange point-free style?**

**What exactly are the primitives of Libretto, from which everything else is derived?**

**Does libretto support dynamic stream topologies?**

**Do libretto streams support feedback loops?**

**Is there support for timers?**

**How do I communicate with the external world (I/O)?**

**Does libretto support supervision of a subsystem?**

**Can I execute different parts of the system on different execution contexts/thread pools?**

**Does libretto have fibers, as known from ZIO or Cats Effect?**

**Where is the IO monad?**

**You criticize monadic IO for hiding the program structure inside impenetrable Scala functions. However,
  Libretto allows to incorporate Scala functions and dynamically computed Scala values into the system as well.
  Are Libretto programs any more amenable to inspection than IO programs?**

**Does libretto support session types?**

**Why are there two different function-like symbols? What is the difference between `-⚬` and  `=⚬`?**

|         `-⚬`         |          `=⚬`           | notes |
|:--------------------:|:-----------------------:|-------|
| Describes a program. | Lives inside a program. |       |
| Tangible: we create and manipulate _Scala_ values of this type. | Intangible: there are no _Scala_ values of this type. A type like `A =⚬ B` can appear only to the left or right of `-⚬`. |  |
| Pure value. As such, it can be used any number of times (including zero). | Must be treated as a resource, i.e. consumed (evaluated) exactly once, because it might have captured other resources that are consumed on evaluation. |  |
| Morphism | Exponential object | In category theory, one does not look inside objects. Everything is expressed in terms of morphisms. In particular, objects are not viewed as collections of elements. This is analogous to there being no values of type `=⚬`, or of types <code>&#124;*&#124;</code>, <code>&#124;+&#124;</code>, <code>&#124;&amp;&#124;</code>, `One`, `Val`, ..., which are all objects in a category. We express everything in terms of morphisms `-⚬`. |

**How do I type the `⚬` symbol used in `-⚬` and `=⚬`?**