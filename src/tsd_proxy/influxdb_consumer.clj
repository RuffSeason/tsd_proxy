(ns tsd_proxy.influxdb_consumer)

(use 'aleph.http 'lamina.time 'lamina.connections 'lamina.core 'gloss.core)

(require '[clojure.tools.logging :as log])

(defn number->string [s]
  (try
    (let [n (read-string s)]
      (if (number? n) n s))
    (catch RuntimeException e
      s)))

(defn string-to-tv-tupples [s]
  (clojure.string/split s #"="))

(defn combiner [[ts vs] [t v]]
  [(conj ts t) (conj vs v)])

(defn extract-tags-and-values [t-v-strings]
  "Takes ['k1=v1' 'k2=v2' 'k3=v3'] and returns [['k1' 'k2' 'k3'] ['v1' 'v2' 'v3]]"
  (reduce combiner [[] []] (map string-to-tv-tupples (sort t-v-strings))))

(defn make-influxdb-metric [tcollector-metric-line]
  "converts 'put proc.loadavg 1430641159 0.2 type=1m host=foo' into a
   hash that looks like {'proc.loadavg' {'columns' ('time' 'value'
   'host' 'type'), 'points' [(1430641159, 0.2, 'foo', '1m')]}}"
  (let [metric-string-parts (clojure.string/split tcollector-metric-line #"\s+")]
    (if (>= (count metric-string-parts) 4)
      (let [[_ metric-name ts value & t-v-strings] metric-string-parts
            [tags values] (extract-tags-and-values t-v-strings)]
        {metric-name {"columns" (into ["time" "value"] tags)
                      "points" [(map number->string (into [ts value] values))]}})
      (log/warn "Invalid metric:" tcollector-metric-line))))

(defn merge-points [m1 m2]
  {"columns" (get m1 "columns")
   "points" (into (get m1 "points") (get m2 "points"))})

(defn aggregate-metrics [influxdb-metrics]
  "influxdb-metrics is a seq of hashes that look like {'metric-name'
   {'columns' (c1 c2 ..), 'points' [(p1 p2 ..)]}}.  We aggregate the
   influxdb-metrics based on the metric name.  The hash is then
   transformed into a seq that looks like ({'name' 'metric-name',
   'columns' (c1 c2 ..), 'points' ((p11 p12 ..) (p21 p22 ..))} ..)"
  (let [grouped-metrics (apply merge-with merge-points influxdb-metrics)]
    (map
     (fn [[k v]] (into {"name" k} v))
     grouped-metrics)))

(defn post-datapoints-to-influxdb [url json-body]
  (try
    (let [response (sync-http-request
                    {:method :post
                     :url url
                     :body json-body})
          response-code (:status response)]
      (= 200 response-code))
    (catch Exception ex
      (log/warn "Got exception:" ex)
      false)))

(defn send-to-influxdb [queue-ch url cancel-cb-fn]
  (let [num-points (count queue-ch)]
    (if (= num-points 0)
      (log/info "Queue empty, nothing to send to Influxdb")
      (let [influxdb-data-points (aggregate-metrics
                                  (map make-influxdb-metric
                                       (channel->lazy-seq
                                        (take* num-points queue-ch))))
            json-body (clojure.data.json/write-str influxdb-data-points)]

        (wait-for-result
         (run-pipeline
          1

          (fn [attempt-num]
            (log/info "Attempt:" attempt-num "sending" num-points "metrics to Influxdb.")
            (if (post-datapoints-to-influxdb url json-body)
              (do
                (log/info "Added" num-points "metrics to Influxdb.")
                (complete true))
              (do
                (log/warn "Failed to add datapoints, sleeping for 1s before trying again.")
                (inc attempt-num))))

          (wait-stage 1000)

          (fn [attempt-num]
            (restart attempt-num))))))))

(defn make-consumer [url]
  "This consumer lets metrics queue up for 2s, and then sends them to
   influxdb as a batch (makes it more efficient for influxdb)."
  (let [qch (permanent-channel)]
    (invoke-repeatedly 2000 (partial send-to-influxdb qch url))
    qch))

