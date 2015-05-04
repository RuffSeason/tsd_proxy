(ns tsd_proxy.influxdb_consumer)

(use 'aleph.http 'lamina.time 'lamina.core 'gloss.core)

(require '[clojure.tools.logging :as log])

(defn number->string [s]
  (try
    (let [n (read-string s)]
      (if (number? n) n s))
    (catch RuntimeException e
      s)))

(defn make-influxdb-metric [tcollector-metric-line]
  "converts 'put proc.loadavg 1430641159 0.2 type=1m host=foo' into a
   hash that looks like {'proc.loadavg' {'columns' ('time' 'value'
   'host' 'type'), 'points' (1430641159, 0.2, 'foo', '1m')}}"
  (let [metric-string-parts (clojure.string/split tcollector-metric-line #"\s+")]
    (if (> (count metric-string-parts) 2)
      (let [[_ metric-name ts value & t-v-pairs] metric-string-parts
            tag-value-pairs (sort
                             (into {}
                                   (map
                                    (fn [t-v-pair]
                                      (clojure.string/split t-v-pair #"="))
                                    t-v-pairs)))
            tags (into [] (map first tag-value-pairs))
            values (into [] (map second tag-value-pairs))]
        {metric-name {"columns" (into ["time" "value"] tags)
                      "points" [(map number->string (into [ts value] values))]}})
      (log/warn "Invalid metric:" tcollector-metric-line))))

(defn merge-points [m1 m2]
  {"columns" (get m1 "columns")
   "points" (into (get m1 "points") (get m2 "points"))})

(defn aggregate-metrics [influxdb-metrics]
  "influxdb-metrics is a seq of hashes that look like {'metric-name'
   {'columns' (c1 c2 ..), 'points' (p1 p2 ..)}}.  We aggregate the
   influxdb-metrics based on the metric name.  The hash is then
   transformed into a seq that looks like ({'name' 'metric-name',
   'columns' (c1 c2 ..), 'points' ((p11 p12 ..) (p21 p22 ..))} ..)"
  (let [grouped-metrics (apply merge-with merge-points influxdb-metrics)]
    (map
     (fn [[k v]] (into {"name" k} v))
     grouped-metrics)))

(defn send-to-influxdb [queue-ch url cancel-cb-fn]
  (let [num-points (count queue-ch)]
    (if (> num-points 0)
      (let [influxdb-data-points (aggregate-metrics
                                  (map make-influxdb-metric
                                       (channel->lazy-seq
                                        (take* num-points queue-ch))))]
        (wait-for-result
         (run-pipeline
          nil
          {:error-handler
           (fn [ex]
             (log/warn "Got http exception:" ex)
             (log/warn "Could not send msg id:"
                       (System/identityHashCode influxdb-data-points)
                       "to Influxdb, re-trying in 1s.")
             (Thread/sleep 1000)
             (restart nil))}
          (fn [_]
            (let [response-code (:status
                                 (sync-http-request
                                  {:method :post
                                   :url url
                                   :body (clojure.data.json/write-str influxdb-data-points)}))]
              (when (not (= 200 response-code))
                (log/warn "Got response code:" response-code "from Influxdb, retrying in 1s.")
                (Thread/sleep 1000)
                (restart nil))))))))))

(defn make-consumer [url]
  "This consumer lets metrics queue up for 2s, and then sends them to
   influxdb as a batch (makes it more efficient for influxdb)."
  (let [qch (permanent-channel)]
    (lamina.time/invoke-repeatedly 2000 (partial send-to-influxdb qch url))
    (log/info "Connected to:" url)
    qch))

