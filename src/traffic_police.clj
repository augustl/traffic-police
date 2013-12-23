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
    (list* resource (mapcat #(flatten-resource resource %) children))))

(defn flatten-resources
  [resources]
  (mapcat #(flatten-resource ["" []] %) resources))

(defn compile-resources
  [resources middleware-wrapper]
  (map
   (fn [[route-path route-preconditions route-handlers]]
     {:route (clout.core/route-compile route-path)
      :route-preconditions route-preconditions
      :wrapped-route-handlers (zipmap
                               (keys route-handlers)
                               (map
                                (fn [handler]
                                  (middleware-wrapper
                                   (fn [req]
                                     (when-let [processed-req (run-preconditions (:__tp-preconditions req) req)]
                                       (handler processed-req)))))
                                (vals route-handlers)))})
   (flatten-resources resources)))

(defn- chained-resources
  [resources]
  (fn [req]
    (some
     (fn [{:keys [route route-preconditions wrapped-route-handlers]}]
       (when-let [route-match (clout.core/route-matches route {:path-info (get-request-path req)})]
         (if-let [handler (get wrapped-route-handlers (get-request-method req))]
           (handler (-> req
                        (assoc-route-params route-match)
                        (assoc :__tp-preconditions route-preconditions)))
           (get-method-not-allowed-response req))))
     resources)))

(defn chained-handlers
  "Chains a list of request handlers, returning the response of the first
   handler in the list that returns something truthy."
  [& handlers]
  (fn [req]
    (some #(% req) handlers)))

(defn handler
  ([resources]
     (handler identity resources))
  ([middleware-wrapper resources]
     (chained-resources (compile-resources resources middleware-wrapper))))

(defmacro r
  "Convenience macro for a larger indentation level in most editors."
  [path precondition handlers & children]
  `[~path ~precondition ~handlers ~@children])
