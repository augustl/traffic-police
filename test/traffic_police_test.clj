(ns traffic-police-test
  (:require [traffic-police :as t])
  (:use clojure.test))

;; TODO: Write more tests. The code in src/ is a copy/paste
;; from another project where it was integration tested via
;; the tests for the app itself.

(deftest flattening-resources
  (let [fn-a (fn [])
        fn-b (fn [])
        fn-c (fn [])]
    (is (= (t/flatten-resources
            [["/foo" fn-a {}
              ["/bar" fn-b {}]
              ["/baz" fn-c {}]]])
           [["/foo" [fn-a] {}]
            ["/foo/bar" [fn-a fn-b] {}]
            ["/foo/baz" [fn-a fn-c] {}]]))))

(deftest default-negotiator-with-passing-negotiator)

(deftest default-negotiator-with-breaking-negotiator)