(ns traffic-police-test
  (:require [traffic-police :as t])
  (:use clojure.test))

(defn echo-handler
  [req]
  {:status 200 :body (:uri req)})

(deftest flattening-resources
  (let [fn-a (fn [])
        fn-b (fn [])
        fn-c (fn [])
        fn-d (fn [])]
    (is (= (t/flatten-resources
            [["/foo" fn-a {}
              ["/bar" fn-b {}]
              ["/baz" fn-c {}
               ["/maz" fn-d {}]]]])
           [["/foo" [fn-a] {}]
            ["/foo/bar" [fn-a fn-b] {}]
            ["/foo/baz" [fn-a fn-c] {}]
            ["/foo/baz/maz" [fn-a fn-c fn-d] {}]]))))

(deftest handlers
  (testing "basic handler"
    (let [handler (t/handler
                   [["/foo" identity {:get echo-handler}]])]
      (is (= "/foo"
             (:body (handler {:uri "/foo" :request-method :get}))))
      (is (= nil
             (handler {:uri "/wat" :request-method :get})))))

  (testing "basic nested handler"
    (let [handler (t/handler
                   [["/foo" identity {:get echo-handler}
                     ["/bar" identity {:get echo-handler}]]])]
      (is (= "/foo"
             (:body (handler {:uri "/foo" :request-method :get}))))
      (is (= "/foo/bar"
             (:body (handler {:uri "/foo/bar" :request-method :get}))))))

  (testing "basic nested handler with params"
    (let [handler (t/handler
                   [["/foo" identity {:get echo-handler}
                     ["/:foo-id" identity {:get echo-handler}
                      ["/:bar-id" identity {:get echo-handler}]]]])]
      (is (= "/foo/123"
             (:body (handler {:uri "/foo/123" :request-method :get}))))
      (is (= "/foo/123/abc"
             (:body (handler {:uri "/foo/123/abc" :request-method :get}))))))

  (testing "basic pre-condition"
    (let [handler (t/handler
                   [["/foo/:foo-id" (fn [req] (if (= (-> req :route-params :foo-id) "123") req))
                     {:get echo-handler}]])]
      (is (= "/foo/123"
             (:body (handler {:uri "/foo/123" :request-method :get}))))
      (is (= nil
             (handler {:uri "/foo/456" :request-method :get})))))

  (testing "nested pre-conditions"
    (let [handler (t/handler
                   [["/foo" (fn [req] (assoc req :test1 "123"))
                     {}
                     ["/:foo-id" (fn [req] (assoc req :test2 (-> req :route-params :foo-id)))
                      {}
                      ["/bar" identity
                       {:get (fn [req] {:body (str (:test1 req) (:test2 req))})}]]]])]
      (is (= "123wat"
             (:body (handler {:uri "/foo/wat/bar" :request-method :get}))))))

  (testing "request method"
    (let [handler (t/handler
                   [["/foo" identity {:get echo-handler :post echo-handler}]])]
      (is (= "/foo"
             (:body (handler {:uri "/foo" :request-method :get}))))
      (is (= "/foo"
             (:body (handler {:uri "/foo" :request-method :post}))))
      (is (= 405
             (:status (handler {:uri "/foo" :request-method :delete}))))))

  (testing "basic middleware wrapper"
    (let [wrap-test1-appender (fn [handler]
                                (fn [req]
                                  (handler (assoc req :test1 (str (:test1 req) "abc")))))
          wrap-test1-assigner (fn [handler]
                                (fn [req]
                                  (handler (assoc req :test1 "123"))))
          handler (t/handler
                   (fn [handler]
                     (-> handler
                         wrap-test1-appender
                         wrap-test1-assigner))
                   [["/foo" (fn [req] (assoc req :test2 "wat")) {:get (fn [req] {:body (str (:test1 req) "-" (:test2 req))})}]])]
      (is (= "123abc-wat"
             (:body (handler {:uri "/foo" :request-method :get}))))))

  (testing "nested middleware wrapper"
    (let [wrap-test-appender (fn [handler]
                               (fn [req]
                                 (handler (assoc req :test "omgwat"))))
          handler (t/handler
                   (fn [handler]
                     (-> handler
                         wrap-test-appender))
                   [["/foo" identity {}
                     ["/bar" identity {:get (fn [req] {:body (:test req)})}]]])]
      (is (= "omgwat"
             (:body (handler {:uri "/foo/bar" :request-method :get}))))))

  (testing "middleware wrapper runs before precinditions"
    (let [wrap-test-appender (fn [handler]
                               (fn [req]
                                 (let [res (handler (assoc req :test "omg"))]
                                   (assoc res :other-thing "hmz"))))
          handler (t/handler
                   (fn [handler]
                     (-> handler
                         wrap-test-appender))
                   [["/foo" (fn [req] (assoc req :test (str (:test req) "wat")))
                     {:get (fn [req] {:body (:test req)})}]])]
      (is (= "omgwat"
             (:body (handler {:uri "/foo" :request-method :get}))))
      (is (= "hmz"
             (:other-thing (handler {:uri "/foo" :request-method :get})))))))

(defrecord CustomRequest [fancy-method nice-path dem-route-params])
(extend-protocol t/TrafficPoliceRequest
  CustomRequest
  (get-request-method [req] (:fancy-method req))
  (get-request-path [req] (:nice-path req))
  (assoc-route-params [req route-params] (assoc req :dem-route-params route-params)))

(deftest custom-request-test
  (testing "bells and whistles"
    (let [handler (t/handler
                   [["/foo" identity
                     {}
                     ["/:foo-id" identity
                      {:get (fn [req] {:body (:dem-route-params req)})}]]])]
      (is (= {:foo-id "123"}
             (:body (handler (CustomRequest. :get "/foo/123" nil))))))))
