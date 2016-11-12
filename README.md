# xf-sand

A practice of Transducers in Clojure.

# PROBLEM#1

Here is a sample data.

    (def d
      [#{1}
       #{\a \b \c \d \e}
       #{"aa" "bb"}
       #{[1] {:k 'v} #{}}])

Count elements of the data and transform them to strings.

## An ordinary way

    (->> d
         (map count)
         (map str)) ;;=> ("1" "5" "2" "3")


If you compose them...

    (def count-str
      (comp str count)) ;; be careful of the order: count first, then str

`map` only once to get the same result.

    (map count-str d)

## With transducers

    ;; these three are all transducer
    (def count-t
      (map count))
    (def str-t
      (map str))
    (def count-str-t
      (comp count-t str-t)) ;; the order is different from above!!

In action:

    (reduce (count-str-t conj) [] d)  ;;=> ["1" "5" "2" "3"]
    ;; map can be written in reduce you know

Or more transducer way:

    (transduce count-str-t conj d)

# PROBLEM#2

In addition to PROBLEM#1, filter "5" out and take first two elements

## A utility

    (defn not5
      [s]
      (not= s "5"))

## But...

You can't have a composition of `count-str` and `not3`, so...

    (->> d
         (map count-str)
         (filter not5)
         (take 2))    ;;=> ("1" "2")

## Good news!

Transducers are composable.

    (def not5-t
      (filter not5))
    (def take2-t
      (take 2))
    (def all-t
      (comp count-str-t not5-t take2-t))

And action:

    (transduce all-t conj d)    ;;=> ["1" "2"]

# So what are transducers?

## Terminology

|Term|Definition|
|----|----------|
|Reducing function(*rf*)|Functions to be used in the first argument to `reduce`.|
|Transformer|Like `count` and `str` in the above examples, functions transform a thing to another.|

## Transducers

Transducers(*xf*) are "*Transformer for reducing functions.*"

They are composable. And as you doing so, you don't care about:

- the source context (is it a collection, stream, or channel?)
- the output context (you want a collection or scalar value?)

You don't care about them until you `reduce`/`transduce` for the final output.

    ;; from a collection to a collection:
    (transduce all-t conj d)    ;;=> ["1" "2"]

    ;; or to a scalar value:
    (transduce all-t str d)     ;;=> "12"

`conj` and `str` here tell the context. And the transducer `all-t` creates the right *rf* for you. Above two lines are same as follows:

    (def rf-v (all-t conj))
    (reduce rf-v (rf-v) d)

    (def rf-s (all-t str))
    (reduce rf-s (rf-s) d)

## Reducing functions

### Multiple arity

Reducing functions(such as `rf-v` and `rf-s`) should have 0-arity, 1-arity, and 2-arity versions.

    (def rf-v (all-t conj))
    (rf-v)                ;;=> []
    (rf-v ["1"] #{1 2})   ;;=> ["1" "2"]
    (rf-v ["1" "2"])      ;;=> ["1" "2"]

    (def rf-s (all-t str))
    (rf-s)              ;;=> ""
    (rf-s "1" #{1 2})   ;;=> "12"
    (rf-s "12")         ;;=> "12"

- 0-arity version is used to create initial value when it's not supplied to `reduce`
- 2-arity version do the reduction
- 1-arity version is called once after reducing is done and returns final output

### Early termination

When reducing is finished before consuming all inputs, the 2-arity version calls `reduced`. You can see if it's done or not with `reduced?`. When it is, use `deref` to get the final value. And you can't re-use the reducing function any more.
