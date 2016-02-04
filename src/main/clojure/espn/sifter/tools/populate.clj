(ns espn.sifter.tools.populate
  (:require [clojure.data.json :as json])
  (:import (org.apache.kafka.clients.producer KafkaProducer ProducerRecord)
           (java.util Date )
           (org.apache.kafka.common.serialization StringSerializer)))

(def producer (new KafkaProducer {"bootstrap.servers" "dp-dev-kb0-metadata.dev.dp.pvt:9092"
                                  "value.serializer"     StringSerializer
                                  "key.serializer" StringSerializer}))

(defn producer []  (new KafkaProducer {"bootstrap.servers" "dp-dev-kb0-metadata.dev.dp.pvt:9092"
                                  "value.serializer"     StringSerializer
                                  "key.serializer" StringSerializer}))

(producer)


(defn random-event []
  {:time (. (new Date) getTime)
   :stuff {:foo (java.util.UUID/randomUUID)
           :bar (java.util.UUID/randomUUID)}})
(defn send [s]
  (let [rec (new ProducerRecord "dp-coolbetm-fake-log-topic" nil s )]
    (. producer send rec)))

(for [_ (range 2000000)]
  (-> (random-event)
      (json/write-str)
      (send)))
