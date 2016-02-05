(ns espn.sifter.kafka
  (:require [clojure.data.json :as json])
  (:import (com.espn.data.kafka.raw SimplePoolingConsumerConnector Cursor SimpleMessageStreamer)
           (org.apache.kafka.common TopicPartition)))

(def ^:dynamic *brokers*)
(def ^:dynamic *client-id*)
(def ^:dynamic *cxn-pool-size*)
(def ^:dynamic *topic*)
(def ^:dynamic *block-size*)

(def get-cxtr (let [get-cxtr (fn [connstr client-id pool-size]
                                (println "Constructor args: " connstr client-id pool-size)
                                (new SimplePoolingConsumerConnector connstr client-id pool-size))
                     get-cxtr (memoize get-cxtr)]
                 (fn [] (get-cxtr *brokers* *client-id* *cxn-pool-size*))))

(defn unmarshal [s]
  (try {:struct (json/read-str s :key-fn keyword)
        :raw    s}
    (catch Exception e {:raw s})))





(defn for-block [instant action]
  (let [cxtr         (get-cxtr)
        offsetFinder (. cxtr getOffsetFinder)
        offset       (. offsetFinder offsetBefore instant *topic* 0)
        cursor       (new Cursor cxtr *block-size*)
        partition    (new TopicPartition *topic* 0)
        _            (. cursor put partition offset)
        m            (. cursor poll)
        msgs         (. m get partition)]
    (doseq [msg msgs]
      (->> msg (.toString)
               (unmarshal)
               (hash-map :offset (. msg getOffset) :event)
               (action)))))




(defn for-tail [action]
  (let [cxtr  (get-cxtr)
        strmr (new SimpleMessageStreamer cxtr)
        strm  (. strmr tailIterator *topic* 100000)]
    (doseq [msg strm]
      (->> msg (.toString)
               (unmarshal)
               (hash-map :offset (. msg getOffset) :event)
               (action)))))


