(ns sifter.compiler.main
  (:require [clojure.string :refer [split]]
            [clojure.edn :as edn]
            [clojure.stacktrace :as trace]
            [sifter.compiler.parser :as parser :refer [->Fragment]]
            [sifter.compiler.error :refer [->CompilerError]])
  (:import (sifter.compiler.error CompilerError)
           (sifter.compiler.parser StringLiteral Fragment EqualitySign Conjunction)))


(defrecord AndKw [pos])
(defrecord OrKw [pos])
(defrecord NotKw [pos])

(defrecord AndCjx [pos children])
(defrecord OrCjx [pos children])
(defrecord NotCjx [pos children])

(defrecord Comparison [lhs pos rhs])

(defn- cjx? [astnode]
  (or (instance? AndCjx astnode)
      (instance? OrCjx astnode)
      (instance? NotCjx astnode)
      (instance? Conjunction astnode)))


(defmacro defstage [name arglist & body]
  (let [arg (arglist 0)]
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




(def display);;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; This stage prints the AST to the console for visual inspection.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
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

    (instance? AndCjx ast) (do (print-with-tabs tab-level "[BEGIN AND]")
                               (doseq [x (:children ast)]
                                 (print-out x (inc tab-level)))
                               (print-with-tabs tab-level "[END AND]"))

    (instance? OrCjx ast) (do (print-with-tabs tab-level "[BEGIN OR]")
                              (doseq [x (:children ast)]
                                (print-out x (inc tab-level)))
                              (print-with-tabs tab-level "[END OR]"))

    (instance? NotCjx ast) (do (print-with-tabs tab-level "[BEGIN NOT]")
                               (doseq [x (:children ast)]
                                 (print-out x (inc tab-level)))
                               (print-with-tabs tab-level "[END NOT]"))

    :else (print-with-tabs tab-level ast)))

(defn- display [ast delim]
  (println delim)
  (print-out ast 0)
  (println delim)
  ast)





(def enclose-in-and);;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Wrap the AST in an enclosing AND
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defstage enclose-in-and [ast]
          (update ast :children (partial cons (->Fragment nil "and"))))




(def tag-conjunctions);;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Convert leading fragments into keywords
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- tag-cjx-header [fragment]
  (let [pos (:pos fragment)
        s (:content fragment)]
    (cond
      (= s "and") (->AndKw pos)
      (= s "AND") (->AndKw pos)
      (= s "And") (->AndKw pos)
      (= s "or") (->OrKw pos)
      (= s "OR") (->OrKw pos)
      (= s "Or") (->OrKw pos)
      (= s "not") (->NotKw pos)
      (= s "NOT") (->NotKw pos)
      (= s "Not") (->NotKw pos)
      :else (->CompilerError "invalid conjunction"
                             (:pos fragment)))))


(defn- tag-cjx-hd-and-tail [children]
  (cons (->> children (first) (tag-cjx-header))
        (->> children (rest) (map tag-conjunctions))))

(defstage tag-conjunctions [ast]
          (cond
            (instance? EqualitySign ast) ast
            (instance? Fragment ast) ast
            (instance? StringLiteral ast) ast
            (empty? (:children ast)) (->CompilerError "empty parentheses"
                                                      (:pos ast))

            :else (validate (update ast :children tag-cjx-hd-and-tail))))
















(def elevate-cjx-tags);;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Convert each conjunction to a typed conjunction based on its leading keyword
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def elevator {AndKw ->AndCjx
               OrKw  ->OrCjx
               NotKw ->NotCjx})


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
                (instance? StringLiteral ast)) ast

            :else (-> ast (elevate-cjx-tag) (validate))))


(def tag-comparisons);;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Combine (fragment = "string") into a comparison subtree
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- comptag-reducer [state elem]
  (let [out (:out state)
        cur (:cur state)]
    (cond
      ;;Currenly holding nothing for each input type
      (and (nil? cur)
           (instance? Fragment elem)) (assoc state :cur elem)

      (and (nil? cur)
           (instance? StringLiteral elem)) (update state :out conj elem)

      (and (nil? cur)
           (instance? EqualitySign elem)) (reduced (->CompilerError "unexpected ="
                                                                    (:pos elem)))

      (and (nil? cur)
           (cjx? elem)) (let [xformed (-> elem
                                          (tag-comparisons)
                                          (validate))]
                          (if (error? xformed)
                            (reduced xformed)
                            {:out (conj out xformed)
                             :cur nil}))

      ;;Currently holding a fragment
      (and (instance? Fragment cur)
           (instance? Fragment elem)) {:out (conj out cur)
                                       :cur elem}

      (and (instance? Fragment cur)
           (instance? StringLiteral elem)) {:out (conj out cur elem)
                                            :cur nil}

      (and (instance? Fragment cur)
           (instance? EqualitySign elem)) (assoc state :cur (->Comparison cur
                                                                          (:pos elem)
                                                                          nil))

      (and (instance? Fragment cur)
           (cjx? elem)) (let [xformed (-> elem
                                          (tag-comparisons)
                                          (validate))]
                          (if (error? xformed)
                            (reduced xformed)
                            {:out (conj out cur xformed)
                             :cur nil}))


      ;;Currently holding an unfinished comparison
      (and (instance? Comparison cur)
           (instance? Fragment elem)) {:out (conj out (->Comparison (:lhs cur)
                                                                    (:pos cur)
                                                                    elem))
                                       :cur nil}

      (and (instance? Comparison cur)
           (instance? StringLiteral elem)) {:out (conj out (->Comparison (:lhs cur)
                                                                         (:pos cur)
                                                                         elem))
                                            :cur nil}

      (and (instance? Comparison cur)
           (instance? EqualitySign elem)) (reduced (->CompilerError "unexpected ="
                                                                    (:pos elem)))

      (and (instance? Comparison cur)
           (cjx? elem)) (reduced (->CompilerError "unexpected ="
                                                  (:pos elem)))

      :else (reduced (->CompilerError "there is a bug"
                                      nil)))))

(defstage tag-comparisons [cjx]
          (let [result (reduce comptag-reducer
                               {:out [] :cur nil}
                               (:children cjx))]
            (cond
              (error? result) result

              (instance? Comparison (:cur result)) (->CompilerError "incomplete comparison"
                                                                    (:pos (:cur result)))

              (instance? Fragment (:cur result)) (assoc cjx :children (conj (:out result)
                                                                            (:cur result)))

              (nil? (:cur result)) (assoc cjx :children (:out result))

              :else (->CompilerError "there is certainly a bug"
                                     nil))))



(def check-arities);;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;NOT can only have one child
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defstage check-arities [ast]
          (if (not (cjx? ast))
            ast
            (let [kids (map check-arities (:children ast))
                  cnt (count kids)]
              (if (and (instance? NotCjx ast)
                       (not= cnt 1))
                (->CompilerError "NOT should have exactly one argument" (:pos ast))
                (-> ast (update :children (partial map check-arities)) (validate))))))



(def check-strings);;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Make sure string literals are valid
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defstage check-strings [ast]

          (cond
            (cjx? ast) (-> ast
                           (update :children (partial map check-strings))
                           (validate))

            (instance? StringLiteral ast) (try (update ast :content edn/read-string)
                                               (catch Exception e
                                                 (->CompilerError "invalid string literal"
                                                                  (:pos ast))))
            (instance? Comparison ast) (update ast :rhs check-strings)

            :else ast))



(def gen-callable);;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Generate a clojure function from the AST
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn event-contains-text [event ^String text]
  ;(println "Examining:" text (. (:raw event) contains text) event)
  (. (:raw event) contains text))

(defn- string-match-expr [pattern]
  (list 'espn.sifter.compiler.main/event-contains-text 'event pattern))

(defn- comparison-expr [lhs rhs]
  (let [segments (split lhs #"\.")
        segments (map keyword segments)
        segments (into [:struct] segments)]
    (list '= rhs (list 'get-in 'event segments))))



(defstage gen-expr [ast]
          (cond
            (instance? AndCjx ast) `(and ~@(map gen-expr (:children ast)))

            (instance? OrCjx ast) `(or ~@(map gen-expr (:children ast)))

            (instance? NotCjx ast) `(not ~@(map gen-expr (:children ast)))

            (instance? StringLiteral ast) (string-match-expr (:content ast))

            (instance? Fragment ast) (string-match-expr (:content ast))

            (instance? Comparison ast) (comparison-expr (:content (:lhs ast))
                                                        (:content (:rhs ast)))

            :else (throw (new Exception "WAT"))))


(defstage gen-predicate [ast]
          (list 'fn ['event]
                ast))

(defstage gen-callable [form]
  (eval form))







(defn compile-filter [text]
  (if (nil? text)
    (->CompilerError "No filter specified" nil)
    (try (-> (parser/parse text)
             (enclose-in-and)
             (tag-conjunctions)
             (elevate-cjx-tags)
             (tag-comparisons)
             (check-arities)
             (check-strings)
             (gen-expr)
             (gen-predicate)
             (display "=================================================================")
             (gen-callable))
      (catch Exception e (trace/print-stack-trace e)))))




;(def spec "a=b (and\"abc\\n\" ) (NOT x) b=\"=\"")

;(def spec "(or (and a (or b c)) a.b.c = \"foo\")")


;(def sieve (compile-filter spec))


;sieve

;(println [(sieve {:raw "ab"})
;          (sieve {:raw "ac"})
;          (sieve {:raw "ad"})
;          (sieve {:raw "ad" :struct {:a {:b {:c "foo"}}}})
;          ])



