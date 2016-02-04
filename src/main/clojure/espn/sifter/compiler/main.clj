(ns espn.sifter.compiler.main
  (:require [clojure.pprint :as pprint]
            [clojure.stacktrace :as trace]
            [espn.sifter.compiler.parser :as parser :refer [->Fragment]]
            [espn.sifter.compiler.error :refer [->CompilerError]]
            )
  (:import (espn.sifter.compiler.error CompilerError)
           (espn.sifter.compiler.parser StringLiteral Fragment EqualitySign Conjunction)))


(defrecord AndKw [pos])
(defrecord OrKw  [pos])
(defrecord NotKw [pos])

(defrecord AndCjx [pos children])
(defrecord OrCjx [pos children])
(defrecord NotCjx [pos children])


(defmacro defstage [name  arglist & body]
  (let [ arg (arglist 0)]
    `(defn- ~name ~arglist
       (if (instance? CompilerError ~arg)
         ~arg
         (do ~@body)))))

(defn- error? [val]
  (if (instance? CompilerError val)
    val
    false))

(defn- validate [astnode]
  (let [error (some error? (:children astnode))]
    (if error error astnode)))




(defn- print-with-tabs [tab-level s]
  (dotimes [_ tab-level]
    (print \tab))
  (println s))

(defn- print-out [ast tab-level]
  (cond
    (instance? Conjunction ast) (do (print-with-tabs tab-level "[BEGIN]")
                                    (doseq [x (:children ast)]
                                      (print-out x (inc tab-level)))
                                    (print-with-tabs tab-level "[END]"))

    (instance? AndCjx ast)      (do (print-with-tabs tab-level "[BEGIN AND]")
                                    (doseq [x (:children ast)]
                                      (print-out x (inc tab-level)))
                                    (print-with-tabs tab-level "[END AND]") )

    (instance? OrCjx ast)       (do (print-with-tabs tab-level "[BEGIN OR]")
                                    (doseq [x (:children ast)]
                                      (print-out x (inc tab-level)))
                                    (print-with-tabs tab-level "[END OR]"))

    (instance? NotCjx ast)      (do (print-with-tabs tab-level "[BEGIN NOT]")
                                    (doseq [x (:children ast)]
                                      (print-out x (inc tab-level)))
                                    (print-with-tabs tab-level "[END NOT]"))

    :else                       (print-with-tabs tab-level ast)))

(defn- display [ast delim]
  (println delim)
  (print-out ast 0)
  (println delim)
  ast)

(defstage enclose-in-and [ast]
  (update ast :children (partial cons (->Fragment nil "and"))))


(defn- tag-cjx-header [fragment]
  (let [pos (:pos fragment)
        s   (:content fragment)]
    (cond
      (= s "and") (->AndKw pos)
      (= s "AND") (->AndKw pos)
      (= s "And") (->AndKw pos)
      (= s "or")  (->OrKw pos)
      (= s "OR")  (->OrKw pos)
      (= s "Or")  (->OrKw pos)
      (= s "not") (->NotKw pos)
      (= s "NOT") (->NotKw pos)
      (= s "Not") (->NotKw pos)
      :else       (->CompilerError "invalid conjunction"
                                   (:pos fragment)))))

(def tag-conjunctions)

(defn- tag-cjx-hd-and-tail [children]
  (cons (->> children (first) (tag-cjx-header))
        (->> children (rest) (map tag-conjunctions))))

(defstage tag-conjunctions [ast]
  (cond
    (instance? EqualitySign ast)     ast
    (instance? Fragment ast)         ast
    (instance? StringLiteral ast)    ast
    (empty? (:children ast))         (->CompilerError "empty parentheses"
                                                             (:pos ast))

    :else                            (validate (update ast :children tag-cjx-hd-and-tail))))


(def elevator {AndKw ->AndCjx
               OrKw  ->OrCjx
               NotKw ->NotCjx})

(def elevate-cjx-tags)

(defn- elevate-cjx-tag [cjx]
  (let [kids (:children cjx)
        cjx-tag (first kids)
        args (rest kids)
        args (map elevate-cjx-tags args)
        pos (:pos cjx)]
   (-> cjx-tag
       (type)
       (elevator)
       (apply pos args '()))))

(defstage elevate-cjx-tags [ast]
  (cond
    (or (instance? EqualitySign ast)
        (instance? Fragment ast)
        (instance? StringLiteral ast))    ast

    :else           (-> ast (elevate-cjx-tag) (validate))))



(defstage codegen [ast]
  `(fn [_#] true))









 (defn make-sieve [text]
   (try
     (-> (parser/parse text)
         (enclose-in-and)
         (tag-conjunctions)
         (elevate-cjx-tags)
         (display "=================================================================")
         (codegen)
         ;(display "=================================================================")
         (eval))
     (catch Exception e (trace/print-stack-trace e))))

(def spec "= (and\"abc\" = ) (NOT=) ==")
;(def spec "= (and==)")

(make-sieve spec)


