(ns tsd_proxy.kafka_consumer)

(use 'lamina.core 'lamina.connections 'clj-kafka.producer)

(require '[clojure.tools.logging :as log])

(require 'clj-kafka.zk)

(defn send-to-kafka [queue-ch consumer-ch producer metric-string]
  (let [metric-string-parts (clojure.string/split metric-string #"\s")]
    (if (> (count metric-string-parts) 2)
      (let [metric-parts (rest metric-string-parts)
            metric-name (.getBytes (first metric-parts))
            trimmed-message (.getBytes  (clojure.string/join " " metric-parts))]
        (run-pipeline nil
                      {:error-handler (fn [ex]
                                        (log/warn "Kafka exception:" ex)
                                        ; Could not send the msg to
                                        ; kafka.  Close the sink
                                        ; channel and re-queue the
                                        ; message into the main queue.
                                        (log/warn "Could not send msg id:"
                                                  (System/identityHashCode metric-string)
                                                  "to Kafka, re-queueing it.")
                                        (if (close consumer-ch)
                                          (do
                                            (enqueue queue-ch metric-string)
                                            (.close producer)))
                                        (complete nil))}
                      (fn [_]
                        (send-message producer
                                      (message "metrics"
                                               metric-name
                                               trimmed-message)))))
      (log/warn "Invalid metric:" metric-string))))

(defn connect-qch-to-consumer [source-ch consumer-ch]
  (log/info "Connected to Kafka brokers.")
  (join source-ch consumer-ch))

(defn make-kafka-client [queue-ch zk-string]
  (let [kafka-brokers (clj-kafka.zk/broker-list
                       (clj-kafka.zk/brokers {"zookeeper.connect" zk-string}))
        kafka-producer (producer {"metadata.broker.list" kafka-brokers})
        consumer-ch (channel)]
    (log/info "Kafka client is setup to talk to brokers at:" kafka-brokers)
    (receive-in-order consumer-ch (partial send-to-kafka queue-ch consumer-ch kafka-producer) )
    (success-result consumer-ch)))

(defn make-consumer [zk-string]
  (let [qch (permanent-channel)
        consumer (pipelined-client
                  #(make-kafka-client qch zk-string)
                  {:on-connected (partial connect-qch-to-consumer qch)
                   :retry? true})]
    ; the consumer is lazy, prime it by sending an empty string.
    (consumer "")
    qch))

