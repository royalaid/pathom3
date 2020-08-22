(ns com.wsscode.pathom3.connect.runner-test
  (:require
    [clojure.test :refer [deftest is are run-tests testing]]
    [com.wsscode.pathom3.connect.indexes :as pci]
    [com.wsscode.pathom3.connect.operation :as pco]
    [com.wsscode.pathom3.connect.planner :as pcp]
    [com.wsscode.pathom3.connect.runner :as pcr]
    [com.wsscode.pathom3.entity-tree :as p.ent]
    [com.wsscode.pathom3.format.shape-descriptor :as pfsd]
    [com.wsscode.pathom3.test.geometry-resolvers :as geo]
    [edn-query-language.core :as eql]))

(deftest all-requires-ready?-test
  (is (= (pcr/all-requires-ready? (p.ent/with-cache-tree {} {:a 1})
           {::pcp/requires {}})
         true))

  (is (= (pcr/all-requires-ready? (p.ent/with-cache-tree {} {:a 1})
           {::pcp/requires {:a {}}})
         true))

  (is (= (pcr/all-requires-ready? (p.ent/with-cache-tree {} {:a 1})
           {::pcp/requires {:b {}}})
         false)))

(deftest merge-resolver-response!-test
  (testing "does nothing when response is not a map"
    (is (= (-> (pcr/merge-resolver-response!
                 (p.ent/with-cache-tree {} {:foo "bar"})
                 nil)
               ::p.ent/cache-tree* deref)
           {:foo "bar"})))

  (testing "adds new data to cache tree"
    (is (= (-> (pcr/merge-resolver-response!
                 (p.ent/with-cache-tree {::pcp/graph {::pcp/nodes     {}
                                                      ::pcp/index-ast {}}} {:foo "bar"})
                 {:buz "baz"})
               ::p.ent/cache-tree* deref)
           {:foo "bar"
            :buz "baz"}))))

(deftest run-node!-test
  (is (= (let [tree  {::geo/left 10 ::geo/width 30}
               env   (p.ent/with-cache-tree (pci/register {} geo/registry)
                                            tree)
               graph (pcp/compute-run-graph
                       (-> env
                           (assoc
                             ::pcp/available-data (pfsd/data->shape-descriptor tree)
                             :edn-query-language.ast/node (eql/query->ast [::geo/right
                                                                           ::geo/center-x]))))
               env   (assoc env ::pcp/graph graph)]
           (pcr/run-node! env (pcp/get-root-node graph))
           @(::p.ent/cache-tree* env))
         {::geo/left       10
          ::geo/width      30
          ::geo/right      40
          ::geo/half-width 15
          ::geo/center-x   25})))

(defn run-graph [env tree query]
  (let [env   (-> env
                  (p.ent/with-cache-tree tree))
        ast   (eql/query->ast query)
        graph (pcp/compute-run-graph
                (-> env
                    (assoc
                      ::pcp/available-data (pfsd/data->shape-descriptor tree)
                      :edn-query-language.ast/node ast)))]
    (pcr/run-graph! (assoc env ::pcp/graph graph))
    @(::p.ent/cache-tree* env)))

(defn coords-resolver [c]
  (pco/resolver 'coords-resolver {::pco/output [::coords]}
                (fn [_ _] {::coords c})))

(deftest run-graph!-test
  (is (= (run-graph (pci/register geo/registry)
                    {::geo/left 10 ::geo/width 30}
                    [::geo/right ::geo/center-x])
         {::geo/left       10
          ::geo/width      30
          ::geo/right      40
          ::geo/half-width 15
          ::geo/center-x   25}))

  (testing "processing sequence of consistent elements"
    (is (= (run-graph (pci/register [geo/full-registry])
                      {:data {::geo/x 10}}
                      [{:data [:left]}])
           {:data {::geo/x    10
                   ::geo/left 10
                   :left      10}}))

    (is (= (run-graph (pci/register [geo/full-registry
                                     (coords-resolver
                                       [{::geo/x 7 ::geo/y 9}
                                        {::geo/x 3 ::geo/y 4}])])
                      {}
                      [{::coords [:left]}])
           {::coords [{::geo/x 7 ::geo/y 9 ::geo/left 7 :left 7}
                      {::geo/x 3 ::geo/y 4 ::geo/left 3 :left 3}]}))

    (testing "data from join"
      (is (= (run-graph (pci/register geo/full-registry)
                        {::coords [{::geo/x 7 ::geo/y 9}
                                   {::geo/x 3 ::geo/y 4}]}
                        [{::coords [:left]}])
             {::coords [{::geo/x 7 ::geo/y 9 ::geo/left 7 :left 7}
                        {::geo/x 3 ::geo/y 4 ::geo/left 3 :left 3}]}))))

  (testing "processing sequence of inconsistent maps"
    (is (= (run-graph (pci/register geo/full-registry)
                      {::coords [{::geo/x 7 ::geo/y 9}
                                 {::geo/left 7 ::geo/y 9}]}
                      [{::coords [:left]}])
           {::coords
            [{::geo/x    7
              ::geo/y    9
              ::geo/left 7
              :left      7}
             {::geo/left 7
              ::geo/y    9
              :left      7}]})))

  (testing "processing sequence partial items being maps"
    (is (= (run-graph (pci/register geo/full-registry)
                      {::coords [{::geo/x 7 ::geo/y 9}
                                 20]}
                      [{::coords [:left]}])
           {::coords [{::geo/x    7
                       ::geo/y    9
                       ::geo/left 7
                       :left      7}
                      20]}))))
