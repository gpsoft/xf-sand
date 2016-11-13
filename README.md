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

    ;; these three are all transducers
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

Usually reducing functions are just something that take two arguments(an accumulated value and an input value) and return new accumulated value. They actually have more specific requirements to be used with transducers.

### Multi-arity

Reducing functions should have 0-arity, 1-arity, and 2-arity versions.

    (def rf1 (count-str-t conj))

    (rf1)                    ;;=> []

    (rf1 ["1"] #{1 2})       ;;=> ["1" "2"]
    (rf1 ["1" "2"] #{1 2 3}) ;;=> ["1" "2" "3"]

    (rf1 ["1"])              ;;=> ["1"]
    (rf1 ["1" "2"])          ;;=> ["1" "2"]

- 0-arity version(init) is used to create initial value when it's not supplied to `reduce`
- 2-arity version(step) does the reduction
- 1-arity version(completion) is called once after reducing is done and returns final output

Let's define one.

    (defn rf-csv
      ([] "")
      ([acc s]
       (if (empty? acc)
         s
         (str acc "," s)))
      ([acc] (str "/" acc "/")))
    (transduce count-str-t rf-csv d)  ;;=> "/1,5,2,3/"
    (transduce all-t rf-csv d)        ;;=> "/1,2/"

### Early termination

If reducing is finished before consuming all inputs, the 2-arity version can call `reduced`. Then `reduce`/`transduce` will know it by using `reduced?` and stop reduction.

    (defn rf-csv-until3
      ([] "")
      ([acc s]
       (if (= s "3")
         (reduced acc)
         (if (empty? acc)
           s
           (str acc "," s))))
      ([acc] (str "/" acc "/")))
    (transduce count-str-t rf-csv-until3 d)  ;;=> "/1,5,2/"

`(reduced acc)` returns a wrapped value of `acc` and you need to use `deref` to take `acc` out of it. So the 2-arity version can return two different types:

 - normal `acc` ...when reducing process is continuing
 - a wrapped value of `acc` ...when reducing was terminated

If you use a value from a 2-ariity version of *rf* inside of a during process, don't forget to check which type it is before refering it.

    ....(let [result (rf acc inp)]
          (if (reduced? result)
            then      ;; it's wrapped
            else))    ;; not wrapped

The source code of [clojure.core/reductions](https://github.com/clojure/clojure/blob/clojure-1.8.0/src/clj/clojure/core.clj#L6928) will help you.

## Transducers with state

Remember `take2-t` transducer?

    (def take2-t (take 2))
    (def rf-t2 (take2-t conj))

The reducing function `rf-t2` should have a state inside to remember the number "How many times it has added elements to the accumulator so far."

    ;; count 1
    (let [acc (rf-t2 [] 1)] (reduced? acc))   ;;=> false
    ;; count 2, and done
    (let [acc (rf-t2 [] 1)] (reduced? acc))   ;;=> true

The state won't be reset even if you called the 1-arity version.

    (rf-t2 [])
    (let [acc (rf-t2 [] 1)] (reduced? acc))   ;; still true

So you can't *re-use* `rf-t2`. You'd better create a fresh reducing function for each reduction process.

# PROBLEM#3

Define a function that creates a transducer. Given `n`, `f`, and `p`, the transducer applies `f` to the first `n` elements that matches the predicate `p`.

#### Ex:

    (def nfp-t (mk-nfp-t 3 inc even?))
    (transduce nfp-t conj [1 2 3 4 5 6 7 8 9])  ;;=> [1 3 3 5 5 7 7 8 9]
    ;; only 2, 4, and 6 are incremented

#### A solution:

    (defn mk-nfp-t
      [n f p]
      (fn [rf]
        (let [cnt (atom n)]
          (fn
            ([] (rf))
            ([acc] (rf acc))
            ([acc e]
             (let [match (p e)
                   ee (f e)]
               (if match
                 (if (pos? @cnt)
                   (do (swap! cnt dec)
                       (rf acc ee))
                   (rf acc e))
                 (rf acc e))))))))

    (def nfp-t (mk-nfp-t 3 inc even?))
    (transduce nfp-t conj [1 2 3 4 5 6 7 8 9])  ;;=> [1 3 3 5 5 7 7 8 9]
    (transduce
      (comp (map #(* % 2)) nfp-t str-t take2-t)
      rf-csv [1 2 3 4 5 6 7 8 9])               ;;=> "/3,5/"

