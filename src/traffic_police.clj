(ns traffic-police
  (:require clout.core)
  (:import clojure.lang.IPersistentMap))

(defprotocol TrafficPoliceRequest
  (get-request-method [req])
  (get-request-path [req])
  (assoc-route-params [req route-params])
  (get-method-not-allowed-response [req]))

(extend-protocol TrafficPoliceRequest
  IPersistentMap
  (get-request-method [req] (:request-method req))
  (get-request-path [req] (or (:uri req) (:path-info req))) ;; TODO: Figure out why we need path-info.
  (assoc-route-params [req route-params] (assoc req :route-params route-params))
  (get-method-not-allowed-response [req] {:status 405}))

(defn- run-preconditions
  [preconditions req]
  (if (not (nil? req))
    (if (empty? preconditions)
      req
      (recur (rest preconditions) ((first preconditions) req)))))

(defn- flatten-resource
  [[parent-path parent-preconditions] [path precondition handlers & children]]
  (let [resource [(str parent-path path)
                  (conj parent-preconditions precondition)
                  handlers]]
    (concat
     [resource]
     (mapcat #(flatten-resource resource %) children))))

(defn flatten-resources
  [resources]
  (mapcat #(flatten-resource ["" []] %) resources))

(defn compile-resources
  [resources middleware-wrapper]
  (map
   (fn [[route-path route-preconditions route-handlers]]
     (let [route (clout.core/route-compile route-path)
           wrapped-route-handlers (zipmap
                                   (keys route-handlers)
                                   (map middleware-wrapper (vals route-handlers)))]
       (fn [req]
         (when-let [route-match (clout.core/route-matches route {:path-info (get-request-path req)})]
           (if-let [handler (get wrapped-route-handlers (get-request-method req))]
             (when-let [processed-req (run-preconditions route-preconditions (assoc-route-params req route-match))]
               (handler processed-req))
             (get-method-not-allowed-response req))))))
   (flatten-resources resources)))

(defn identity-middleware-wrapper
  [handler]
  (fn [req]
    (handler req)))

(defn handler
  ([resources] (handler identity-middleware-wrapper resources))
  ([middleware-wrapper resources]
     (let [routes (compile-resources resources middleware-wrapper)]
       (fn [req]
         (some #(% req) routes)))))

(defn chained-handlers
  [& handlers]
  (fn [req]
    (some #(% req) handlers)))

(defmacro r
  "The only purpose of this macro is to construct a vector identical
   to its arguments. The `r` function call makes the nested structure
   look better. You're free to just create the nested vector directiy
   without using this macro."
  [path precondition handlers & children]
  `[~path ~precondition ~handlers ~@children])
