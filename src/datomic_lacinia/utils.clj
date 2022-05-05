(ns datomic-lacinia.utils
  (:require [clojure.test :refer [deftest- is testing]]))

(defn uppercase-first [s]
  (if (empty? s)
    s
    (str (.toUpperCase (subs s 0 1)) (subs s 1))))

(deftest- uppercase-first-test
  (is (= (uppercase-first "hellO") "HellO"))
  (is (= (uppercase-first "s") "S"))
  (is (= (uppercase-first "S") "S"))
  (is (= (uppercase-first "") ""))
  (is (= (uppercase-first nil) nil)))

(defn lowercase-first [s]
  (if (empty? s)
    s
    (str (.toLowerCase (subs s 0 1)) (subs s 1))))

(deftest- lowercase-first-test
  (is (= (lowercase-first "HellO") "hellO"))
  (is (= (lowercase-first "S") "s"))
  (is (= (lowercase-first "s") "s"))
  (is (= (lowercase-first "") ""))
  (is (= (lowercase-first nil) nil)))

(defn paths
  "Transforms a nested map into a seq of [[k1 k2 ...] leaf-val] pairs."
  [m]
  (when m
    (letfn [(collect [keys m]
              (when m
                (if (map? m)
                  (mapcat (fn [[k v]] (collect (conj keys k) v)) m)
                  (if (and (sequential? m) (not-empty m))
                    (mapcat (fn [e] (collect keys e)) m)
                    [[keys m]]))))]
      (collect [] m))))

(deftest- paths-test
  (testing "simple case"
    (is (= (paths {:referencedBy {:track {:artists {:track {:name "Moby Dick"}}}}})
           '([[:referencedBy :track :artists :track :name] "Moby Dick"]))))

  (testing "single element array"
    (is (= (paths {:referencedBy {:track {:artists [{:track {:name "Moby Dick"}}]}}})
           '([[:referencedBy :track :artists :track :name] "Moby Dick"]))))

  (testing "multiple element array"
    (is (= (paths {:referencedBy {:track {:artists [{:track {:name "Moby Dick"}}
                                                    {:track {:name "Ramble On"}}]}}})
           '([[:referencedBy :track :artists :track :name] "Moby Dick"]
             [[:referencedBy :track :artists :track :name] "Ramble On"]))))

  (testing "edge cases"
    (is (= (paths nil) nil))
    (is (= (paths {}) '()))
    (is (= (paths {:arrayValue []}) '([[:arrayValue] []])))))
