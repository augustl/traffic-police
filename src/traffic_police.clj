(ns traffic-police
  (:require clout.core))

(defn- flatten-resource
  [parent tree-resource]
  (let [resource [(str (nth parent 0) (nth tree-resource 0))
                  (conj (nth parent 1)
                        (nth tree-resource 1))
                  (nth tree-resource 2)]]
    (concat
     [resource]
     (mapcat
      #(flatten-resource resource %)
      (drop 3 tree-resource)))))

(defn- run-preconditions
  [preconditions req]
  (if (nil? req)
    nil
    (if (empty? preconditions)
      req
      (recur (rest preconditions) ((first preconditions) req)))))

(defn flatten-resources
  [resources]
  (mapcat
   (fn [tree-resource]
     (let [resource [(nth tree-resource 0)
                     [(nth tree-resource 1)]
                     (nth tree-resource 2)]]
       (concat
        [resource]
        (mapcat
         #(flatten-resource resource %)
         (drop 3 tree-resource)))))
   resources))

(defn identity-negotiator
  [handler req]
  (handler req))

(defn default-negotiator
  [negotiators handler req]
  (((apply comp (reverse negotiators)) handler) req))

(defmulti get-request-method (fn [req] (:_request-type req)))
(defmethod get-request-method nil
  [req]
  (:request-method req))

(defmulti assoc-route-params (fn [req match] (:_request-type req)))
(defmethod assoc-route-params nil
  [req match]
  (assoc req :route-params match))

(defmulti get-clout-compliant-req (fn [req] (:_request-type req)))
(defmethod get-clout-compliant-req nil
  [req]
  req)

(defn resources
  [negotiator-fn & resources]
  (let [routes (map
                (fn [resource]
                  (let [route (clout.core/route-compile (nth resource 0))
                        preconditions (nth resource 1)
                        handlers (nth resource 2)]
                    (fn [req]
                      (when-let [route-match (clout.core/route-matches route (get-clout-compliant-req req))]
                        (if-let [handler ((get-request-method req) handlers)]
                          (negotiator-fn
                           (fn [req]
                             (when-let [processed-req (run-preconditions preconditions req)]
                               (handler processed-req)))
                           (assoc-route-params req route-match))
                          {:status 405})))))
                (flatten-resources resources))]
    (fn [req]
      (some #(% req) routes))))

(defmacro negotiate
  [& negotiators]
  `(fn [handler# req#]
     (~default-negotiator [~@negotiators] handler# req#)))

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