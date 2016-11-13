(ns xf-sand.core)

;; data
(def d
  [#{1}
   #{\a \b \c \d \e}
   #{"aa" "bb"}
   #{[1] {:k 'v} #{}}])

;;; PROBLEM#1
;;; Count elements and to-string

;; an ordinary way:
(->> d
     (map count)
     (map str)) ;;=> ("1" "5" "2" "3")

;; if you compose them...
(def count-str
  (comp str count)) ;; be careful of the order: count first, then str

;; map only once to get the same result
(map count-str d)

;; with transducers:
(def count-t        ;; a transducer
  (map count))
(def str-t
  (map str))
(def count-str-t
  (comp count-t str-t)) ;; reversed order!!

;; in action
(reduce (count-str-t conj) [] d)  ;;=> ["1" "5" "2" "3"]
(transduce count-str-t conj d)    ;; or more transducer way

;;; PROBLEM#2
;;; In addition to PROBLEM#1,
;;; filter "5" out and take first two elements

;; a utility
(defn not5
  [s]
  (not= s "5"))

;; you can't comp count-str and not3
;; so...
(->> d
     (map count-str)
     (filter not5)
     (take 2))    ;;=> ("1" "2")

;; good news: transducers are composable
(def not5-t
  (filter not5))
(def take2-t
  (take 2))
(def all-t
  (comp count-str-t not5-t take2-t))

;; and action
(transduce all-t conj d)    ;;=> ["1" "2"]


;; conj here is a reducing function
;; transducers go with any reducing functions:
(transduce all-t str d)     ;;=> "12"

;; they actually create reducing functions:
(def rf-v (all-t conj))
(reduce rf-v (rf-v) d)    ;;=> ["1" "2"]

(def rf-s (all-t str))
(reduce rf-s (rf-s) d)    ;;=> "12"


;; reducing functions come with three overloaded versions:
(def rf1 (count-str-t conj))
(rf1)                    ;;=> []
(rf1 ["1"] #{1 2})       ;;=> ["1" "2"]
(rf1 ["1" "2"] #{1 2 3}) ;;=> ["1" "2" "3"]
(rf1 ["1"])              ;;=> ["1"]
(rf1 ["1" "2"])          ;;=> ["1" "2"]

;; my reducing function
(defn rf-csv
  ([] "")
  ([acc s]
   (if (empty? acc)
     s
     (str acc "," s)))
  ([acc] (str "/" acc "/")))
(transduce count-str-t rf-csv d)  ;;=> "/1,5,2,3/"
(transduce all-t rf-csv d)        ;;=> "/1,2/"

;; early termination
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


;; reducing function may have a state (it's not necessarily pure!)
(def rf-t2 (take2-t conj))
(let [acc (rf-t2 [] 1)] (reduced? acc))   ;;=> false
(let [acc (rf-t2 [] 1)] (reduced? acc))   ;;=> true
(rf-t2 [])
(let [acc (rf-t2 [] 1)] (reduced? acc))   ;;=> true (it didn't reset the state)


;;; PROBLEM#3
;;; Define a function that creates a transducer.
;;; Given n, f, and p, the transducer applies f to the first n elements
;;; that matches the predicate p.
;;; Ex: (def nfp-t (mk-nfp-t 3 inc even?))
;;;     (transduce nfp-t conj [1 2 3 4 5 6 7 8 9])  ;;=> [1 3 3 5 5 7 7 8 9]

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
