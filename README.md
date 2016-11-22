# xf-sand

A practice of Transducers in Clojure.

日本語版は→[「ClojureでTransducer」](http://gpsoft.dip.jp/hiki/?Clojure%E3%81%A7Transducer)

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

You can't have a composition of `count-str` and `not5`, so...

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

Reducing functions should be 3-arity.

    (def rf1 (count-str-t conj))

    (rf1)                    ;;=> []

    (rf1 ["1"] #{1 2})       ;;=> ["1" "2"]
    (rf1 ["1" "2"] #{1 2 3}) ;;=> ["1" "2" "3"]

    (rf1 ["1"])              ;;=> ["1"]
    (rf1 ["1" "2"])          ;;=> ["1" "2"]

- arity-0 version(Init) is used to create initial value when it's not supplied to `reduce`
- arity-2 version(Step) does the reduction
- arity-1 version(Completion) is called once after reducing is done and returns final output

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

If reducing is finished before consuming all inputs, the arity-2 version can call `reduced`. Then `reduce`/`transduce` will know it by using `reduced?` and stop reduction.

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

`(reduced acc)` returns a wrapped value of `acc` and you need to use `deref` to take `acc` out of it. So the arity-2 version can return two different types:

 - normal `acc` ...when reducing process is continuing
 - a wrapped value of `acc` ...when reducing was terminated

If you use a value from a 2-ariity version of *rf* inside of a during process, don't forget to check which type it is before refering it.

    ....(let [result (rf acc inp)]
          (if (reduced? result)
            then      ;; it's wrapped
            else))    ;; not wrapped

The source code of [clojure.core/interpose](https://github.com/clojure/clojure/blob/clojure-1.8.0/src/clj/clojure/core.clj#L5002) will help you.

## Transducers with state

Remember `take2-t` transducer?

    (def take2-t (take 2))
    (def rf-t2 (take2-t conj))

The reducing function `rf-t2` should have a state inside to remember the number "How many times it has added elements to the accumulator so far."

    ;; count 1
    (let [acc (rf-t2 [] 1)] (reduced? acc))   ;;=> false
    ;; count 2, and done
    (let [acc (rf-t2 [] 1)] (reduced? acc))   ;;=> true

The state won't be reset even if you called the arity-1 version.

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

# Transducers with channels

Our input context has been limited to collections(vectors to be exact) so far. Transducers are, however, independent from the source context. For example, `core.async` provides a decent set of functions for channels to work with transducers.

    (require '[clojure.core.async :as async])

    (def c (async/chan 1 count-str-t))  ;; input channel
    (def rc (async/reduce conj [] c))   ;; reduce returns the output channel

`chan` takes a transducer and creates an input channel from which `reduce` takes input and do the reduction in conjunction with the transducer. `reduce` returns another channel and you can take the final output from it.

Now we can wait the result and print it.

    (async/take! rc prn)

Then put some values to the input channel.

    (async/put! c #{1})               ;; nothing happens
    (async/put! c #{\a \b \c \d \e})  ;; still nothing
    (async/put! c #{"aa" "bb"})       ;; silent
    (async/close! c)                  ;; ["1" "5" "2"] is printed

Another example(with `d` and `all-t`):

    (let [c (async/chan)
          rc (async/transduce all-t str "" c)]
      (async/take! rc prn)
      (for [s d]
        (async/put! c s)))            ;; "12" is printed

# Other than *transduce*

Many functions in `clojure.core`, such as `map`, `filter`, `take`, `mapcat`, create transducers. And `cat` is a transducer itself that concats inputs.

    (transduce cat conj d)   ;;=> [1 \a \b \c \d \e "aa" "bb" #{} {:k v} [1]]
    (transduce cat str d)    ;;=> "1abcdeaabb#{}{:k v}[1]"

Other than performing reduction with `transduce`, transducers can be used to create iterators, collections, or lazy sequences.

    ;; `eduction` returns an iterable.
    (let [iterable (eduction all-t d)]
      (first (seq iterable))) ;;=> "1"

    ;; `into` returns a collection.
    (into [] all-t d)         ;;=> ["1" "2"]

    ;; `sequence` returns a lazy seq.
    (sequence all-t d)        ;;=> ("1" "2")
    (sequence (map str) [1 2 3] ["apple" "orange"]) ;;=> ("1apple" "2orange")

# Quick review on types

Let's review the types of functions above.

Input and output of PROBLEM#1:

    ;; IN                        -> OUT
    [#{1} #{\a \b \c \d \e} ...] -> ["1" "5" "2" "3"]

    [set] -> [string]

The transformer `count` and the transducer `count-t`:

    count       :: set -> long
    count-t     :: (x, long -> x) -> (x, set -> x)
    ;; Note that it's NOT `(x, set -> x) -> (x, long -> x)`.

`str` and `str-t`:

    str         :: long -> string
    str-t       :: (x, string -> x) -> (x, long -> x)

Generally when you `(comp g f)`, `g` have to be able to take output of `f`. Now `count-t` can take output of `str-t` because it's `(x, set -> x)`, and composed transducer is type of:

    count-str-t :: (x, string -> x) -> (x, set -> x)

That reads `count-str-t` takes an rf whose type is `(x, string -> x)` and returns new rf whose type is `(x, set -> x)`. As we have functions conformed to `(x, string -> x)`, such as `conj` and `str`, we can create new rfs `(x, set -> x)`:

    (def rf1 (count-str-t conj))   ;; rf1 :: coll, set -> coll
    (def rf2 (count-str-t str))    ;; rf2 :: string, set -> string

Finally we can use them to reduce `d` whose elements are sets.

    (reduce rf1 [] d)
    (reduce rf2 "" d)

Done!
